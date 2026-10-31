#!/usr/bin/env python3
"""Validate untrusted PR snapshots before submission; require evidence before review.

Only load this script from a trusted base/default-branch commit. ZIP members are
read in memory, never extracted or executed. The Gradle action generates data;
this consumer alone receives repository write permissions.
"""

import argparse
import base64
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile


WORKFLOW = ".github/workflows/dependency-graph-upload.yml"
CONTEXT = "dependency-graph/pr-snapshots"
CORRELATORS = {"tavernforge-product", "tavernforge-probe"}
LIMIT = 8 * 1024 * 1024
FIELDS = {"version", "sha", "ref", "job", "detector", "manifests", "scanned"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON field")
        result[key] = value
    return result


def decode_json(data):
    def invalid_constant(_):
        raise ValueError("Non-finite JSON value")
    return json.loads(data, object_pairs_hook=unique_object, parse_constant=invalid_constant)


def validate_run(event, repository):
    run = event.get("workflow_run", {})
    require(run.get("repository", {}).get("full_name") == repository, "Wrong source repository")
    require(run.get("path") == WORKFLOW, "Wrong source workflow path")
    require(run.get("event") == "pull_request", "Source event is not pull_request")
    require(run.get("status") == "completed", "Source run is incomplete")
    require(type(run.get("id")) is int and run["id"] > 0, "Invalid source run ID")
    require(bool(re.fullmatch(r"[0-9a-f]{40}", run.get("head_sha", ""))), "Invalid source SHA")
    return run


def read_snapshot_archive(data):
    require(len(data) <= LIMIT, "Snapshot archive exceeds size limit")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        members = archive.infolist()
        require(len(members) <= 10, "Too many archive members")
        files = []
        for member in members:
            parts = member.filename.split("/")
            require(not member.filename.startswith("/") and ".." not in parts
                    and "\\" not in member.filename, "Unsafe archive path")
            require((member.external_attr >> 16) & 0o170000 != 0o120000, "Archive contains a symlink")
            if not member.is_dir():
                files.append(member)
        require(len(files) == 1 and files[0].filename.endswith(".json"), "Expected one JSON snapshot per artifact")
        require(files[0].file_size <= LIMIT, "Expanded snapshot exceeds size limit")
        return decode_json(archive.read(files[0]))


def validate_snapshot(snapshot, run, repository, pull_request, server="https://github.com"):
    require(isinstance(snapshot, dict), "Snapshot must be an object")
    require(type(snapshot.get("version")) is int and snapshot["version"] == 0, "Unsupported snapshot version")
    require(snapshot.get("sha") == run["head_sha"], "Snapshot SHA differs from triggering run")
    match = re.fullmatch(r"refs/pull/([1-9][0-9]*)/merge", snapshot.get("ref", ""))
    require(match is not None, "Snapshot ref must identify a PR merge ref")
    number = int(match[1])
    require(pull_request.get("number") == number and pull_request.get("state") == "open", "PR is missing or closed")
    require(pull_request.get("head", {}).get("sha") == run["head_sha"], "PR head has changed")
    require(pull_request.get("base", {}).get("repo", {}).get("full_name") == repository, "PR targets another repository")
    require(pull_request.get("head", {}).get("repo", {}).get("id") == run.get("head_repository", {}).get("id")
            and run.get("head_repository", {}).get("id") is not None, "PR does not match source head repository")
    job = snapshot.get("job", {})
    require(isinstance(job, dict) and job.get("correlator") in CORRELATORS, "Unknown snapshot correlator")
    require(str(job.get("id")) == str(run["id"]), "Snapshot job does not match triggering run")
    manifests = snapshot.get("manifests")
    require(isinstance(manifests, dict) and bool(manifests), "Snapshot has no manifests")
    require(any(isinstance(m, dict) and isinstance(m.get("resolved"), dict) and m["resolved"]
                for m in manifests.values()), "Snapshot has no resolved dependencies")
    require(isinstance(snapshot.get("detector"), dict), "Snapshot has no detector")
    # Never pass arbitrary artifact fields as HTTP request options. Bind the job
    # URL to GitHub metadata rather than trusting a URL supplied by PR code.
    result = {key: snapshot[key] for key in FIELDS if key in snapshot}
    result["job"] = {"id": str(run["id"]), "correlator": job["correlator"],
                     "html_url": f"{server}/{repository}/actions/runs/{run['id']}"}
    return result, number


def validate_set(snapshots):
    require(len(snapshots) == 2 and {s["job"]["correlator"] for s in snapshots} == CORRELATORS,
            "Both product and probe snapshots are required exactly once")
    require(len({s["ref"] for s in snapshots}) == 1, "Snapshots identify different PRs")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class GitHub:
    def __init__(self):
        self.repository = os.environ["GITHUB_REPOSITORY"]
        require(bool(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", self.repository)), "Invalid repository")
        self.api = os.environ.get("GITHUB_API_URL", "https://api.github.com")
        self.server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
        self.token = os.environ["GH_TOKEN"]
        self.opener = urllib.request.build_opener(NoRedirect)

    def request(self, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(f"{self.api}/repos/{self.repository}/{path}", data=data, headers={
            "Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"})
        return self.opener.open(request, timeout=30)

    def json(self, path, payload=None):
        with self.request(path, payload) as response:
            data = response.read(LIMIT + 1)
            require(len(data) <= LIMIT, "GitHub response exceeds size limit")
            return decode_json(data)

    def artifact(self, artifact_id):
        # The authenticated API redirects to a signed storage URL. Do not forward
        # GITHUB_TOKEN to that separate host (urllib's default redirect would).
        try:
            self.request(f"actions/artifacts/{artifact_id}/zip")
        except urllib.error.HTTPError as error:
            require(error.code == 302, "Artifact download did not return a signed URL")
            location = error.headers.get("Location", "")
        else:
            raise ValueError("Artifact download did not redirect")
        require(urllib.parse.urlsplit(location).scheme == "https", "Artifact URL is not HTTPS")
        with urllib.request.urlopen(location, timeout=30) as response:
            data = response.read(LIMIT + 1)
        require(len(data) <= LIMIT, "Snapshot artifact exceeds size limit")
        return data

    def status(self, sha, state):
        self.json(f"statuses/{sha}", {"state": state, "context": CONTEXT,
                  "description": {"pending": "Validating both PR dependency snapshots",
                                  "success": "Both PR dependency snapshots validated and submitted",
                                  "failure": "PR dependency snapshot validation/submission failed"}[state],
                  "target_url": f"{self.server}/{self.repository}/actions/runs/{os.environ['GITHUB_RUN_ID']}"})


def submit(github, event):
    run = validate_run(event, github.repository)
    github.status(run["head_sha"], "pending")
    try:
        require(run.get("conclusion") == "success", "PR dependency generation did not succeed")
        artifacts = []
        for page in range(1, 21):
            batch = github.json(f"actions/runs/{run['id']}/artifacts?per_page=100&page={page}")["artifacts"]
            artifacts.extend(a for a in batch if a["name"].startswith("dependency-graph_"))
            if len(batch) < 100:
                break
        else:
            raise ValueError("Too many source artifacts")
        require(len(artifacts) == 2, "Expected product and probe snapshot artifacts")
        snapshots = []
        for artifact in artifacts:
            require(not artifact.get("expired") and artifact.get("size_in_bytes", LIMIT + 1) <= LIMIT,
                    "Snapshot artifact expired or oversized")
            snapshot = read_snapshot_archive(github.artifact(artifact["id"]))
            require(isinstance(snapshot, dict), "Snapshot must be an object")
            match = re.fullmatch(r"refs/pull/([1-9][0-9]*)/merge", snapshot.get("ref", ""))
            require(match is not None, "Snapshot has no valid PR ref")
            pr = github.json(f"pulls/{match[1]}")
            validated, _ = validate_snapshot(snapshot, run, github.repository, pr, github.server)
            snapshots.append(validated)
        validate_set(snapshots)
        # Validate every artifact before allowing any write to the dependency graph.
        for snapshot in snapshots:
            response = github.json("dependency-graph/snapshots", snapshot)
            require(response.get("id") is not None, "Submission did not return a snapshot ID")
        github.status(run["head_sha"], "success")
        print("Validated and submitted product and probe PR snapshots.")
    except Exception:
        github.status(run["head_sha"], "failure")
        raise


def snapshots_ready(github, base, head):
    statuses = github.json(f"commits/{head}/statuses?per_page=100")
    status = next((s for s in statuses if s.get("context") == CONTEXT), None)
    if not status or status.get("state") != "success" or status.get("creator", {}).get("login") != "github-actions[bot]":
        return False
    for page in range(1, 51):
        with github.request(f"dependency-graph/compare/{base}...{head}?per_page=100&page={page}") as response:
            warning = response.headers.get("x-github-dependency-graph-snapshot-warnings", "")
            if warning and base64.b64decode(warning, validate=True).decode().strip():
                return False
            data = response.read(LIMIT + 1)
            require(len(data) <= LIMIT, "Dependency comparison exceeds size limit")
            changes = decode_json(data)
            require(isinstance(changes, list), "Invalid dependency comparison")
            if len(changes) < 100:
                return True
    raise ValueError("Dependency comparison exceeds page limit")


def wait_for_snapshots(github, event, timeout, sleep=time.sleep, clock=time.monotonic):
    pr = event["pull_request"]
    base, head = pr["base"]["sha"], pr["head"]["sha"]
    require(all(re.fullmatch(r"[0-9a-f]{40}", value) for value in (base, head)), "Invalid comparison SHAs")
    deadline = clock() + timeout
    while True:
        if snapshots_ready(github, base, head):
            print("Both head snapshots submitted; comparison reports no missing snapshots.")
            return
        require(clock() < deadline, "Verified dependency snapshots unavailable after bounded wait; refusing incomplete review")
        print("Waiting for validated product/probe snapshots and a complete comparison.", flush=True)
        sleep(min(15, max(0, deadline - clock())))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("submit", "wait"))
    parser.add_argument("--timeout", type=int, default=1500)
    args = parser.parse_args()
    require(0 <= args.timeout <= 1500, "Timeout is outside the permitted range")
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as source:
        event = json.load(source)
    github = GitHub()
    if args.command == "submit":
        submit(github, event)
    else:
        wait_for_snapshots(github, event, args.timeout)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Dependency graph gate failed: {error}", file=sys.stderr)
        sys.exit(1)
