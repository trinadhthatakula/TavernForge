#!/usr/bin/env python3
"""Audit fixture integrity/provenance; does not test TavernForge format support."""

import hashlib
import json
from pathlib import Path
import re
import sys


def audit():
    root = Path(__file__).resolve().parent
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    revision = manifest["upstream"]["revision"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Upstream revision is not an immutable SHA")
    revisions = {revision}
    contracts = {}
    for source in manifest.get("contract_sources", []):
        source_revision = source["revision"]
        if not re.fullmatch(r"[0-9a-f]{40}", source_revision) or not re.fullmatch(r"[0-9a-f]{64}", source["sha256"]):
            raise ValueError("Contract source lacks immutable revision/hash")
        revisions.add(source_revision)
        contracts[f"{source['repository']}/blob/{source_revision}/{source['path']}"] = source
    seen = set()
    for entry in manifest["entries"]:
        path = (root / entry["path"]).resolve()
        if not path.is_relative_to(root) or entry["path"] in seen:
            raise ValueError("Unsafe or duplicate manifest path")
        seen.add(entry["path"])
        source_revision = entry["source_revision"]
        if source_revision not in revisions or source_revision not in entry["source_url"]:
            raise ValueError(f"Unpinned source: {entry['path']}")
        if entry["kind"] == "synthetic-contract-fixture":
            source = contracts.get(entry["source_url"])
            if source is None or entry["source_sha256"] != source["sha256"] or not entry.get("derivation"):
                raise ValueError(f"Unattributed synthetic contract case: {entry['path']}")
        if not entry.get("license") or not entry.get("expected"):
            raise ValueError(f"Missing provenance: {entry['path']}")
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError(f"Checksum mismatch: {entry['path']}")
        if path.suffix == ".json":
            json.loads(data)
            resource = root.parent / "src/test/resources/fixtures" / path.name
            if not resource.is_file() or resource.read_bytes() != data:
                raise ValueError(f"Missing or changed JVM fixture copy: {path.name}")

    source = (root / "upstream/sillytavern/tests/tavern-card-validator.test.js").read_text()

    def declared_fields(name):
        match = re.search(rf"const {name} = \[(.*?)\];", source, re.DOTALL)
        if match is None:
            raise ValueError(f"Upstream helper field declaration changed: {name}")
        return re.findall(r"'([^']+)'", match.group(1))

    # Object construction is transcribed from the inspected pinned helper bodies.
    v1 = dict.fromkeys(declared_fields("V1_FIELDS"), "")
    v2_data = dict.fromkeys(declared_fields("V2_DATA_FIELDS"), "")
    v2_data.update(alternate_greetings=[], tags=[], extensions={})
    expected = {
        "st-v1-empty-fields.json": v1,
        "st-v2-empty-fields.json": {
            "spec": "chara_card_v2", "spec_version": "2.0", "data": v2_data,
        },
        "st-v3-envelope-only.json": {
            "spec": "chara_card_v3", "spec_version": "3.0", "data": {},
        },
    }
    for name, value in expected.items():
        actual = json.loads((root / "cases" / name).read_text(encoding="utf-8"))
        if actual != value:
            raise ValueError(f"Materialized helper mismatch: {name}")
    print(f"PASS: {len(seen)} pinned files; {len(expected)} helper objects; checksums and JSON verified.")
    print("This audit does not execute adapter/PNG/Android/interoperability tests; see the verification reports for those results.")


if __name__ == "__main__":
    try:
        audit()
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
