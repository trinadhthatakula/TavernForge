// Development-only reference runner. Source pins and licenses: provenance.json and README.md.
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import assert from 'node:assert/strict';

const here = path.dirname(fileURLToPath(import.meta.url));
const fixtures = path.resolve(here, '../..');
const provenance = JSON.parse(await fs.readFile(path.join(here, 'provenance.json'), 'utf8'));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const parser = await fs.readFile(path.join(fixtures, 'upstream/sillytavern/src/character-card-parser.js'));
const encoder = await fs.readFile(path.join(fixtures, 'upstream/sillytavern/src/png/encode.js'));
assert.equal(sha(parser), provenance.upstream_parser_sha256);
assert.equal(sha(encoder), provenance.upstream_encoder_sha256);
assert.equal(sha(await fs.readFile(path.join(here, 'package-lock.json'))), provenance.reference_package_lock_sha256);
// Copy exact source bytes, preserving imports, into an isolated ES module package. Never patch upstream code.
const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'tavernforge-png-reference-'));
try {
    await fs.mkdir(path.join(temporary, 'png'));
    await fs.writeFile(path.join(temporary, 'package.json'), '{"type":"module"}\n');
    await fs.writeFile(path.join(temporary, 'character-card-parser.js'), parser);
    await fs.writeFile(path.join(temporary, 'png/encode.js'), encoder);
    await fs.symlink(path.join(here, 'node_modules'), path.join(temporary, 'node_modules'), 'dir');
    const reference = await import(pathToFileURL(path.join(temporary, 'character-card-parser.js')).href);
    if (process.argv[2] === '--generate-upstream') {
        const artwork = syntheticArtwork();
        await fs.writeFile(path.join(fixtures, 'cases/png-synthetic-artwork.png'), artwork);
        for (const [name, source] of [['st-writer-v2', 'st-v2-empty-fields'], ['st-writer-v3', 'spec-v3-minimal']]) {
            const json = await fs.readFile(path.join(fixtures, `cases/${source}.json`), 'utf8');
            const bytes = reference.write(artwork, json);
            inspectChunks(bytes); // independent CRC/length oracle
            await fs.writeFile(path.join(fixtures, `cases/${name}.png`), bytes);
            const read = JSON.parse(reference.read(bytes));
            assert.equal(read.spec, 'chara_card_v3');
            console.log(`GENERATED ${name}.png ${sha(bytes)} with exact upstream writer; preferred source ${read.spec}`);
        }
    } else {
        assert.equal(process.argv.length, 3, 'Pass the directory emitted by PngReferenceArtifactsTest');
        const artifactRoot = path.resolve(process.argv[2]);
        const manifest = JSON.parse(await fs.readFile(path.join(artifactRoot, 'manifest.json'), 'utf8'));
        let count = 0;
        for (const row of manifest.outputs) {
            assert.match(row.png, /^[a-z0-9-]+\.png$/);
            assert.match(row.json, /^[a-z0-9-]+\.json$/);
            const bytes = await fs.readFile(path.join(artifactRoot, row.png));
            const expected = await fs.readFile(path.join(artifactRoot, row.json));
            assert.equal(sha(bytes), row.pngSha256);
            assert.equal(sha(expected), row.jsonSha256);
            assert.equal(reference.read(bytes), expected.toString('utf8'), `${row.png}: upstream reader bytes`);
            const chunks = inspectChunks(bytes);
            const cardChunks = chunks.filter(c => c.type === 'tEXt' && /^(chara|ccv3)$/i.test(c.data.subarray(0, c.data.indexOf(0)).toString('latin1')));
            assert.equal(cardChunks.length, 1, `${row.png}: stale alternatives removed`);
            assert.equal(cardChunks[0].data.subarray(0, cardChunks[0].data.indexOf(0)).toString(), row.keyword);
            if (row.original) {
                assert.match(row.original, /^[a-z0-9-]+\.png$/);
                const source = await fs.readFile(path.join(artifactRoot, row.original));
                const nonCard = list => list.filter(c => !(c.type === 'tEXt' && /^(chara|ccv3)$/i.test(c.data.subarray(0, c.data.indexOf(0)).toString('latin1')))).map(c => c.complete);
                assert.deepEqual(nonCard(inspectChunks(source)), nonCard(chunks), `${row.png}: every non-card chunk unchanged`);
                if (row.noOp) assert.deepEqual(bytes, source, `${row.png}: exact no-op`);
            }
            count++;
        }
        assert.equal(count, 6, 'Expected V1/V2/V3 no-op and edited engine outputs');
        console.log(JSON.stringify({ result: 'PASS', outputs: count, upstreamRevision: provenance.upstream_revision,
            node: process.version, assertions: ['pinned upstream read equals exact expected JSON', 'independent CRC/chunk checks', 'correct source-version keyword', 'non-card byte preservation', 'exact no-op'] }, null, 2));
    }
} finally {
    await fs.rm(temporary, { recursive: true, force: true });
}

// Independent bitwise CRC32/chunk oracle, separate from upstream packages and Kotlin production.
function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) {
        crc ^= byte;
        for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    return (crc ^ 0xffffffff) >>> 0;
}
function inspectChunks(bytes) {
    assert.deepEqual(bytes.subarray(0, 8), Buffer.from([137,80,78,71,13,10,26,10]));
    const chunks = [];
    for (let at = 8; at < bytes.length;) {
        assert.ok(at + 12 <= bytes.length);
        const length = bytes.readUInt32BE(at);
        const end = at + length + 12;
        assert.ok(end <= bytes.length);
        const type = bytes.subarray(at + 4, at + 8).toString('ascii');
        assert.equal(crc32(bytes.subarray(at + 4, end - 4)), bytes.readUInt32BE(end - 4));
        chunks.push({type, data: bytes.subarray(at + 8, end - 4), complete: bytes.subarray(at, end)});
        at = end;
        if (type === 'IEND') assert.equal(at, bytes.length);
    }
    assert.equal(chunks[0].type, 'IHDR'); assert.equal(chunks.at(-1).type, 'IEND');
    return chunks;
}
function syntheticArtwork() {
    const chunk = (type, data) => {
        const bytes = Buffer.alloc(data.length + 12);
        bytes.writeUInt32BE(data.length); bytes.write(type, 4, 'ascii'); data.copy(bytes, 8);
        bytes.writeUInt32BE(crc32(bytes.subarray(4, -4)), bytes.length - 4);
        return bytes;
    };
    return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),
        chunk('IHDR', Buffer.from([0,0,0,1,0,0,0,1,8,6,0,0,0])),
        chunk('tEXt', Buffer.from('Comment\0Original TavernForge synthetic 1x1 RGBA; GPL-3.0-or-later')),
        chunk('IDAT', deflateSync(Buffer.from([0,17,34,51,255]))), chunk('IEND', Buffer.alloc(0))]);
}
