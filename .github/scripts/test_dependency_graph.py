import base64
import copy
import io
import json
import unittest
import urllib.error
import zipfile
from unittest.mock import patch

import dependency_graph as gate


REPO = "trinadhthatakula/TavernForge"
SHA = "a" * 40
RUN = {"id": 42, "repository": {"full_name": REPO}, "head_repository": {"id": 123},
       "path": gate.WORKFLOW, "event": "pull_request", "status": "completed",
       "conclusion": "success", "head_sha": SHA}
PR = {"number": 7, "state": "open", "head": {"sha": SHA, "repo": {"id": 123}},
      "base": {"sha": "b" * 40, "repo": {"full_name": REPO}}}


def snapshot(correlator="tavernforge-product"):
    return {"version": 0, "sha": SHA, "ref": "refs/pull/7/merge",
            "job": {"id": "42", "correlator": correlator, "html_url": "https://untrusted.invalid"},
            "detector": {"name": "test", "version": "1", "url": "https://example.com"},
            "manifests": {"build.gradle.kts": {"resolved": {"dep": {"package_url": "pkg:maven/example/dep@1"}}}},
            "scanned": "2026-11-01T00:00:00Z"}


def archive(value, name="dependency-graph-reports/test.json"):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as output:
        output.writestr(name, json.dumps(value))
    return stream.getvalue()


class Response(io.BytesIO):
    def __init__(self, value, headers=None):
        super().__init__(json.dumps(value).encode())
        self.headers = headers or {}


class FakeGitHub:
    repository = REPO
    server = "https://github.com"

    def __init__(self, values=None):
        self.values = values if values is not None else [snapshot(), snapshot("tavernforge-probe")]
        self.submitted = []
        self.statuses = []

    def json(self, path, payload=None):
        if path.startswith("actions/runs/"):
            return {"artifacts": [{"id": i, "name": f"dependency-graph_{i}.json",
                                    "expired": False, "size_in_bytes": 100} for i in range(len(self.values))]}
        if path == "pulls/7":
            return PR
        if path == "dependency-graph/snapshots":
            self.submitted.append(payload)
            return {"id": len(self.submitted)}
        raise AssertionError(path)

    def artifact(self, artifact_id):
        return archive(self.values[artifact_id])

    def status(self, sha, state):
        self.statuses.append((sha, state))


class ValidationTests(unittest.TestCase):
    def test_fork_run_and_both_snapshots_are_accepted(self):
        github = FakeGitHub()
        gate.submit(github, {"workflow_run": RUN})
        self.assertEqual(len(github.submitted), 2)
        self.assertEqual(github.statuses, [(SHA, "pending"), (SHA, "success")])

    def test_missing_snapshot_fails_without_submission(self):
        github = FakeGitHub([snapshot()])
        with self.assertRaisesRegex(ValueError, "Expected product and probe"):
            gate.submit(github, {"workflow_run": RUN})
        self.assertEqual(github.submitted, [])
        self.assertEqual(github.statuses[-1], (SHA, "failure"))

    def test_validate_all_before_any_submission(self):
        forged = snapshot("tavernforge-probe")
        forged["sha"] = "c" * 40
        github = FakeGitHub([snapshot(), forged])
        with self.assertRaisesRegex(ValueError, "SHA differs"):
            gate.submit(github, {"workflow_run": RUN})
        self.assertEqual(github.submitted, [])

    def test_failed_generator_does_not_submit(self):
        run = dict(RUN, conclusion="failure")
        github = FakeGitHub()
        with self.assertRaisesRegex(ValueError, "generation did not succeed"):
            gate.submit(github, {"workflow_run": run})
        self.assertEqual(github.submitted, [])

    def test_reject_wrong_event_workflow_and_repository(self):
        for changes in ({"event": "push"}, {"path": "malicious.yml"},
                        {"repository": {"full_name": "other/repo"}}, {"head_sha": "bad"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                gate.validate_run({"workflow_run": dict(RUN, **changes)}, REPO)

    def test_reject_main_ref_wrong_pr_stale_head_and_other_repository(self):
        cases = [(dict(snapshot(), ref="refs/heads/main"), PR),
                 (snapshot(), dict(PR, number=8)),
                 (snapshot(), dict(PR, state="closed")),
                 (snapshot(), dict(PR, head={"sha": "c" * 40, "repo": {"id": 123}})),
                 (snapshot(), dict(PR, head={"sha": SHA, "repo": {"id": 456}})),
                 (snapshot(), dict(PR, base={"repo": {"full_name": "other/repo"}}))]
        for value, pr in cases:
            with self.subTest(value=value, pr=pr), self.assertRaises(ValueError):
                gate.validate_snapshot(value, RUN, REPO, pr)

    def test_reject_wrong_job_empty_graph_and_unknown_correlator(self):
        for changes in ({"job": {"id": "99", "correlator": "tavernforge-product"}},
                        {"job": {"id": "42", "correlator": "unknown"}},
                        {"manifests": {}}, {"manifests": {"empty": {"resolved": {}}}}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                gate.validate_snapshot(dict(snapshot(), **changes), RUN, REPO, PR)

    def test_request_options_are_removed_and_job_url_is_rebound(self):
        value = dict(snapshot(), url="https://evil.invalid", headers={"Authorization": "evil"})
        validated, _ = gate.validate_snapshot(value, RUN, REPO, PR)
        self.assertNotIn("url", validated)
        self.assertNotIn("headers", validated)
        self.assertEqual(validated["job"]["html_url"], f"https://github.com/{REPO}/actions/runs/42")

    def test_duplicate_correlators_fail(self):
        with self.assertRaisesRegex(ValueError, "exactly once"):
            gate.validate_set([snapshot(), snapshot()])

    def test_safe_archive_and_duplicate_json_fields(self):
        self.assertEqual(gate.read_snapshot_archive(archive(snapshot())), snapshot())
        with self.assertRaisesRegex(ValueError, "Duplicate JSON"):
            gate.decode_json('{"sha":"safe","sha":"forged"}')
        with self.assertRaisesRegex(ValueError, "Non-finite"):
            gate.decode_json('{"value":NaN}')

    def test_reject_archive_traversal_and_non_json(self):
        for name in ("../test.json", "/test.json", "folder\\test.json", "run.py"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                gate.read_snapshot_archive(archive(snapshot(), name))

    def test_reject_expanded_archive_size(self):
        data = archive({"large": "x" * 4000})
        with patch.object(gate, "LIMIT", 1000), self.assertRaisesRegex(ValueError, "Expanded snapshot"):
            gate.read_snapshot_archive(data)

    def test_download_redirect_does_not_forward_authorization(self):
        github = object.__new__(gate.GitHub)
        error = urllib.error.HTTPError("https://api.github.com", 302, "Found",
                                       {"Location": "https://storage.example/signed"}, None)
        with patch.object(github, "request", side_effect=error), \
                patch.object(gate.urllib.request, "urlopen", return_value=io.BytesIO(b"zip")) as download:
            self.assertEqual(github.artifact(1), b"zip")
        download.assert_called_once_with("https://storage.example/signed", timeout=30)


class AvailabilityTests(unittest.TestCase):
    def test_success_receipt_and_complete_comparison_are_required(self):
        github = unittest.mock.Mock()
        github.json.return_value = [{"context": gate.CONTEXT, "state": "success",
                                      "creator": {"login": "github-actions[bot]"}}]
        github.request.return_value = Response([])
        self.assertTrue(gate.snapshots_ready(github, "b" * 40, SHA))
        github.request.return_value = Response([], {"x-github-dependency-graph-snapshot-warnings":
                                                   base64.b64encode(b"Missing snapshot").decode()})
        self.assertFalse(gate.snapshots_ready(github, "b" * 40, SHA))
        github.json.return_value = []
        self.assertFalse(gate.snapshots_ready(github, "b" * 40, SHA))

    def test_missing_receipt_fails_after_bounded_wait(self):
        clock = unittest.mock.Mock(side_effect=[0, 0, 0, 15])
        sleep = unittest.mock.Mock()
        with patch.object(gate, "snapshots_ready", return_value=False), \
                self.assertRaisesRegex(ValueError, "refusing incomplete review"):
            gate.wait_for_snapshots(None, {"pull_request": PR}, 15, sleep=sleep, clock=clock)
        sleep.assert_called_once_with(15)


if __name__ == "__main__":
    unittest.main()
