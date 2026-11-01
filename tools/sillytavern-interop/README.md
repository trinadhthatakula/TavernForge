# Pinned receiving-application checks

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). [Observed results and limits](../../docs/testing/SILLYTAVERN_INTEROP.md) distinguish actual receiver behavior from TavernForge's strict format guarantees.

This development-only harness starts the **unmodified SillyTavern 1.19.0 server at commit `06bde939fb1e9c4c8d8641d810f0a916b5bce127`**. It sends synthetic files through its real HTTP import/get/export routes. This extends the smaller pinned PNG reader check; it does not drive a browser or test World Info activation during chat.

## Inputs and isolation

- `provenance.json` pins the official source archive hash, package/lock files, server entry points, receiver routes, World Info UI source and PNG parser. The runner checks all ten source-file hashes before starting.
- The six PNG reference cases and their corresponding JSON must be emitted by `PngReferenceArtifactsTest`; every input is checked against that engine-generated manifest.
- Four existing fixtures are separately identified as fixture probes, not newly emitted engine output: rich V3 character JSON, native World Info, bare V2 CharacterBook and standalone V3 lorebook.
- Use a dedicated extracted upstream tree, never an existing personal SillyTavern installation. The runner creates a fresh evidence/data directory outside the TavernForge repository, picks a loopback port, enables CSRF and keeps cookies/tokens only in memory. Browser launch, plugins, extensions and model/tokenizer downloads are disabled. No provider routes are used.
- The runner stops its own server in `finally`. Raw outputs and logs remain outside Git. The synthetic server's private data directory also contains its generated local session material and must not be committed or uploaded.
- Upstream source remains AGPL-3.0 under its own license; nothing is added to the Android APK. This runner is GPL-3.0-or-later. The full upstream npm tree is development-only and must not be submitted as product dependencies.

## Reproduce

Use Node **26.13.0**, matching the recorded run. Download into a location outside Git; the archive is about 36.3 MiB. The supervisor generates engine output using the existing test:

```sh
./gradlew :format:test --tests '*PngReferenceArtifactsTest' --offline --no-daemon --console plain
```

Download the exact `archiveUrl` in `provenance.json`, verify SHA-256 `d3c5b32e7a1b649366d7d35eaaf877b06376864aacd8713ee8e670ff8ee4099c`, and extract it into a dedicated evidence directory. In that extracted upstream directory run:

```sh
npm ci --omit=dev --ignore-scripts --no-audit --no-fund --prefer-offline --fetch-retries=1 --fetch-timeout=30000
```

From TavernForge root, substitute absolute paths for the isolated tree and evidence parent:

```sh
node --check tools/sillytavern-interop/check.mjs
node tools/sillytavern-interop/check.mjs /absolute/path/SillyTavern-06bde939fb1e9c4c8d8641d810f0a916b5bce127 format/build/png-reference /absolute/path/evidence
```

The runner needs no additional npm dependencies in this repository. It resolves the exact upstream YAML package and PNG parser from the isolated tree. Network access is needed only for initial source/npm setup; requests during the checks target the local server.

## Assertions and interpretation

Six named engine exports (V1/V2/V3 × JSON/PNG) must import successfully, expose the same six core fields through `/api/characters/get`, return those fields through JSON export, and produce PNG exports accepted by the pinned reader. V3 greetings, embedded books and all supplied V3 fields/unknown values are compared at JavaScript JSON-number precision.

The six blank-name engine files are diagnostic cases: schema-valid empty text is not assumed to be usable in the receiver. Non-2xx and HTTP-200 error bodies are both recorded. Fixture probes check World Info storage/readback and preserve the distinction between an accepted array and native `entries` object semantics. Native/bare-book initial stored bytes must match the fixture; a V3 lorebook envelope must return the currently observed HTTP 400.

`PASS_WITH_DOCUMENTED_RECEIVER_LIMITS` means these bounded assertions completed. It is **not** a claim that all six formats have lossless receiver round trips, that a stored bare book behaves as native World Info, or that numeric lexemes survive the upstream JavaScript implementation. See the result document before changing public support claims.
