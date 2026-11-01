# Dependency comparison gate repair

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). This follow-up preserves the [dependency security policy](../planning/DEPENDENCY_SECURITY.md) and the requirement for complete, validated product and probe snapshots before dependency review.

## Reproduced failure

PR #10's Android build, CodeQL and both snapshot submissions passed, but the trusted availability check kept waiting. The exact base was `45ac04d6f79716ed00f1d6cbeadcd3c1137c0275`; the head was `510d6d3fe63cbd75a8d39fce0c3f8d84b573db44`. The base's documentation-only commit had skipped push CI. A trusted manual [base submission run](https://github.com/trinadhthatakula/TavernForge/actions/runs/41331228747) successfully submitted both exact-base graphs.

After submission, the invented `?per_page=100&page=1` URL continued returning 802 dependency additions and an encoded warning that base/head snapshot counts were 0/2. The documented unfiltered URL returned zero changes with an empty snapshot-warning header. These simultaneous results are recorded as a URL-specific cache discrepancy; they do not establish every detail of GitHub's internal caching.

The [official REST contract](https://docs.github.com/en/rest/dependency-graph/dependency-review#get-a-diff-of-the-dependencies-between-commits) documents only the optional `name` filter for this endpoint, not `page` or `per_page`. The previous loop also incorrectly inferred another page whenever a complete response contained 100 or more rows. The observed 802-row response despite `per_page=100` confirms that row-count pagination was invalid.

## Repair and preserved checks

The gate now requests the documented, unfiltered comparison exactly once. It accepts a complete array of any row count within the existing 8 MiB bound, and fails closed on an unexpected next-page Link header. A GitHub Actions success receipt for both exact-head snapshots remains mandatory. Every nonempty snapshot warning still prevents readiness; malformed JSON, duplicate fields, wrong top-level types and oversized responses remain rejected. The bounded wait remains in place. No vulnerability severity threshold, permission or snapshot validation was relaxed.

This checker is loaded from the trusted base revision, so fixing only a PR copy cannot repair the already running gate. The tested checker repair is integrated into the trusted base before the dependent app PR is synchronized and reviewed again. Runtime app code and the installed APK are unaffected by this CI-only repair.

## Verification

`python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'` passes **22 tests**, including seven new regression methods. These verify 100/802-row complete arrays, the exact unfiltered URL, untrusted/failed receipts, empty and large responses with snapshot warnings, malformed warning encoding/JSON, oversized responses and unexpected next links without following them.

The supervisor independently reviewed the diff, repeated all 22 tests and ran the repaired `snapshots_ready` function against live authenticated GitHub responses for the exact base/head above using a read-only GH CLI transport adapter. It returned `True`: both trusted receipts and the complete warning-free comparison were observed. [PR #10](https://github.com/trinadhthatakula/TavernForge/pull/10) links the ensuing complete hosted checks. No API credential or environment file is included in this document or the source changes.
