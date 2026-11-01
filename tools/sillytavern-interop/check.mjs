// GPL-3.0-or-later. Local development-only receiver checks; see README.md.
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import net from 'node:net';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
assert.equal(process.argv.length, 5, 'Usage: node tools/sillytavern-interop/check.mjs UPSTREAM_TREE ENGINE_OUTPUT_DIR EVIDENCE_PARENT');
const [upstream, engine, evidenceParent] = process.argv.slice(2).map(p => path.resolve(p));
assert.ok(evidenceParent !== root && !evidenceParent.startsWith(root + path.sep), 'Evidence and synthetic server data must stay outside Git');
const provenance = JSON.parse(await fs.readFile(path.join(here, 'provenance.json'), 'utf8'));
const sha = b => createHash('sha256').update(b).digest('hex');
for (const [name, expected] of Object.entries(provenance.sourceSha256)) {
    assert.equal(sha(await fs.readFile(path.join(upstream, name))), expected, `Unmodified pinned source: ${name}`);
}
const engineManifest = JSON.parse(await fs.readFile(path.join(engine, 'manifest.json'), 'utf8'));
for (const row of engineManifest.outputs) {
    for (const [file, hash] of [[row.json, row.jsonSha256], [row.png, row.pngSha256]]) {
        assert.match(file, /^[a-z0-9-]+\.(json|png)$/);
        assert.equal(sha(await fs.readFile(path.join(engine, file))), hash, `Engine output ${file}`);
    }
}
const YAML = (await import(pathToFileURL(path.join(upstream, 'node_modules/yaml/dist/index.js')).href)).default;
const reference = await import(pathToFileURL(path.join(upstream, 'src/character-card-parser.js')).href);
await fs.mkdir(evidenceParent, { recursive: true });
const evidence = await fs.mkdtemp(path.join(evidenceParent, 'receiver-'));
const port = await new Promise((resolve, reject) => {
    const s = net.createServer(); s.once('error', reject);
    s.listen(0, '127.0.0.1', () => { const value = s.address().port; s.close(() => resolve(value)); });
});
const config = YAML.parse(await fs.readFile(path.join(upstream, 'default/config.yaml'), 'utf8'));
config.dataRoot = path.join(evidence, 'data'); config.port = port; config.listen = false;
config.protocol = { ipv4: true, ipv6: false }; config.browserLaunch.enabled = false;
config.extensions.enabled = false; config.extensions.autoUpdate = false; config.extensions.models.autoDownload = false;
config.enableDownloadableTokenizers = false; config.enableServerPlugins = false; config.enableServerPluginsAutoUpdate = false;
config.skipContentCheck = true;
const configPath = path.join(evidence, 'config.yaml');
await fs.writeFile(configPath, YAML.stringify(config));
const log = await fs.open(path.join(evidence, 'server.log'), 'w');
const child = spawn(process.execPath, ['server.js', '--configPath', configPath], {
    cwd: upstream, stdio: ['ignore', log.fd, log.fd],
});
let childError;
child.once('error', error => { childError = error; });
const base = `http://127.0.0.1:${port}`;
const cookies = new Map(); let token;
const report = { upstreamRevision: provenance.revision, upstreamVersion: provenance.version, node: process.version,
    sourcePinsChecked: Object.keys(provenance.sourceSha256).length, engineManifestSha256: sha(await fs.readFile(path.join(engine, 'manifest.json'))),
    namedEngineCases: [], blankEngineCases: [], fixtureCases: [], limitations: [] };
const core = ['name', 'description', 'personality', 'scenario', 'first_mes', 'mes_example'];
async function request(endpoint, body) {
    const headers = { Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
    if (token) headers['X-CSRF-Token'] = token;
    if (body && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
    const response = await fetch(base + endpoint, { method: body ? 'POST' : 'GET', headers,
        body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(20_000), redirect: 'error' });
    for (const c of response.headers.getSetCookie()) {
        const value = c.split(';')[0], at = value.indexOf('='); cookies.set(value.slice(0, at), value.slice(at + 1));
    }
    return { status: response.status, bytes: Buffer.from(await response.arrayBuffer()) };
}
const json = result => JSON.parse(result.bytes.toString('utf8'));
function fileForm(bytes, file, type) {
    const form = new FormData(); form.append('avatar', new Blob([bytes]), file);
    if (type) form.append('file_type', type);
    return form;
}
function suppliedSubsetEqual(expected, actual) {
    if (expected === null || typeof expected !== 'object') return expected === actual;
    if (typeof actual !== 'object' || actual === null || Array.isArray(expected) !== Array.isArray(actual)) return false;
    if (Array.isArray(expected) && expected.length !== actual.length) return false;
    return Object.entries(expected).every(([key, value]) => Object.hasOwn(actual, key) && suppliedSubsetEqual(value, actual[key]));
}
async function characterCase(name, bytes, expected, type, enforceCore) {
    const imported = await request('/api/characters/import', fileForm(bytes, name, type));
    let result; try { result = json(imported); } catch { /* status-only diagnostic */ }
    const row = { input: name, inputSha256: sha(bytes), importStatus: imported.status, accepted: !!result?.file_name, receiverErrorFlag: result?.error === true };
    if (!row.accepted) { if (enforceCore) assert.fail(`${name}: receiver rejected named engine output`); return row; }
    const avatar = result.file_name + '.png';
    const get = await request('/api/characters/get', { avatar_url: avatar });
    assert.equal(get.status, 200); const projected = json(get);
    const exported = await request('/api/characters/export', { avatar_url: avatar, format: 'json' });
    assert.equal(exported.status, 200); const actual = json(exported), wanted = expected.data ?? expected;
    row.outputSha256 = sha(exported.bytes); row.sourceSpec = expected.spec ?? 'v1'; row.receiverSpec = actual.spec;
    row.receiverGroupGreetingsPresent = Object.hasOwn(actual.data ?? {}, 'group_only_greetings');
    row.coreFieldsEqual = core.every(k => actual.data?.[k] === wanted[k] && projected.data?.[k] === wanted[k]);
    if (enforceCore) assert.ok(row.coreFieldsEqual, `${name}: known character content changed`);
    if (expected.data?.group_only_greetings) {
        assert.deepEqual(actual.data.group_only_greetings, expected.data.group_only_greetings);
        row.groupGreetingsEqual = true;
    }
    if (expected.data?.character_book) {
        assert.deepEqual(actual.data.character_book, expected.data.character_book);
        row.embeddedLoreEqualAtJsonNumberPrecision = true;
    }
    if (expected.spec === 'chara_card_v3') {
        assert.ok(suppliedSubsetEqual(expected, actual), `${name}: supplied V3 values changed beyond numeric spelling`);
        row.suppliedTreeRetainedAtJsonNumberPrecision = true;
    }
    if (bytes.toString('utf8').includes('123456789012345678901234567890')) {
        row.largeIntegerLexemeRetained = exported.bytes.toString('utf8').includes('123456789012345678901234567890');
    }
    const png = await request('/api/characters/export', { avatar_url: avatar, format: 'png' });
    assert.equal(png.status, 200); const pngJson = JSON.parse(reference.read(png.bytes));
    assert.equal(pngJson.data.name, actual.data.name);
    row.pngExportReadable = true;
    await fs.writeFile(path.join(evidence, `${name}.receiver.json`), exported.bytes);
    await fs.writeFile(path.join(evidence, `${name}.receiver.png`), png.bytes);
    return row;
}
try {
    let ready = false;
    for (let attempts = 0; attempts < 60; attempts++) {
        if (childError || child.exitCode !== null) throw new Error('Pinned receiver failed to start; inspect local server.log');
        try { ready = (await fetch(base, { signal: AbortSignal.timeout(1_000) })).ok; } catch { /* waiting for localhost */ }
        if (ready) break;
        await new Promise(resolve => setTimeout(resolve, 500));
    }
    assert.ok(ready, 'Pinned receiver did not become ready within 30 seconds');
    token = json(await request('/csrf-token')).token;
    for (const version of [1, 2, 3]) {
        for (const kind of ['edited', 'noop']) {
            const expected = JSON.parse(await fs.readFile(path.join(engine, `v${version}-${kind}.json`), 'utf8'));
            for (const type of ['json', 'png']) {
                const name = `v${version}-${kind}.${type}`;
                const row = await characterCase(name, await fs.readFile(path.join(engine, name)), expected, type, kind === 'edited');
                (kind === 'edited' ? report.namedEngineCases : report.blankEngineCases).push(row);
            }
        }
    }
    const fixtureRoot = path.join(root, 'format/fixtures/cases');
    const rich = await fs.readFile(path.join(fixtureRoot, 'spec-v3-rich.json'));
    report.fixtureCases.push({ inputOrigin: 'pinned synthetic fixture; not newly emitted engine output',
        ...await characterCase('spec-v3-rich.json', rich, JSON.parse(rich), 'json', true) });
    for (const name of ['st-world-info-synthetic.json', 'spec-character-book-v2.json', 'spec-lorebook-v3.json']) {
        const bytes = await fs.readFile(path.join(fixtureRoot, name));
        const saved = await request('/api/worldinfo/import', fileForm(bytes, name));
        const row = { input: name, inputOrigin: 'pinned synthetic fixture', inputSha256: sha(bytes), importStatus: saved.status };
        if (saved.status === 200) {
            const fileName = json(saved).name;
            const read = await request('/api/worldinfo/get', { name: fileName }); assert.equal(read.status, 200);
            const got = json(read); assert.deepEqual(got, JSON.parse(bytes));
            row.semanticReadEqualAtJsonNumberPrecision = true;
            row.entriesContainer = Array.isArray(got.entries) ? 'array' : typeof got.entries;
            const stored = await fs.readFile(path.join(config.dataRoot, 'default-user/worlds', fileName + '.json'));
            row.initialStoredBytesEqual = stored.equals(bytes); assert.ok(row.initialStoredBytesEqual);
            row.largeIntegerLexemeRetainedByGet = read.bytes.includes(Buffer.from('123456789012345678901234567890'));
            await fs.writeFile(path.join(evidence, `${name}.receiver-get.json`), read.bytes);
        } else {
            assert.equal(name, 'spec-lorebook-v3.json'); assert.equal(saved.status, 400);
        }
        report.fixtureCases.push(row);
    }
    report.limitations = [
        'Only local HTTP receiver paths are exercised; no browser interaction, chat activation, provider call or external service is used.',
        'SillyTavern normalizes named V1/V2 input into V3 through its own PNG writer/reloader; source-version identity is not preserved by the receiver, and these outputs omit mandatory V3 group_only_greetings.',
        'JavaScript parsing can round large integer sentinels and normalizes numeric lexemes; parsed equality does not prove raw numeric fidelity.',
        'Bare V2 CharacterBook accepted into storage retains an entries array rather than native World Info mapping; this is not evidence of usable World Info semantics.',
        'Standalone lorebook_v3 is rejected by the direct World Info endpoint; no unreviewed conversion is applied.',
        'Blank format-valid names are unreliable in the receiver; HTTP 200 may contain an error and V1 file-name collision behavior can change acceptance.',
        'Rich/lore inputs are existing fixture files and are reported separately from actual named/blank engine exports.',
    ];
    report.result = 'PASS_WITH_DOCUMENTED_RECEIVER_LIMITS';
    await fs.writeFile(path.join(evidence, 'report.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({ result: report.result, namedEngineCases: report.namedEngineCases.length,
        blankEngineCases: report.blankEngineCases.length, fixtureCases: report.fixtureCases.length, report: path.join(evidence, 'report.json') }, null, 2));
} finally {
    cookies.clear(); token = undefined;
    child.kill('SIGTERM');
    if (child.exitCode === null) await Promise.race([new Promise(resolve => child.once('exit', resolve)), new Promise(resolve => setTimeout(resolve, 3_000))]);
    if (child.exitCode === null) child.kill('SIGKILL');
    await log.close();
}
