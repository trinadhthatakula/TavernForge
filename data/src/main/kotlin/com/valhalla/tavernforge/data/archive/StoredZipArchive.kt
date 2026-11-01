package com.valhalla.tavernforge.data.archive

import java.io.EOFException
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32

/** Bounded ZIP32 profile: stored files, no extraction paths, compression or extras. */
internal object StoredZipArchive {
    data class Entry(val name: String, val size: Int, val crc: Long, val localOffset: Long,
        val dataOffset: Long, val flags: Int = 0x800, val time: Int = 0, val date: Int = 33, val neededVersion: Int = 20)
    class Writer(output: OutputStream, private val limits: ArchiveLimits) {
        private val out = LimitedOutput(output, limits.maxArchiveBytes)
        private val entries = mutableListOf<Entry>()
        fun add(name: String, bytes: ByteArray) {
            validName(name)
            if (entries.size >= limits.maxEntries || entries.any { it.name == name }) fail()
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(bytes) }.value
            val start = out.count
            out.u32(0x04034b50); out.u16(20); out.u16(0x800); out.u16(0); out.u16(0); out.u16(33)
            out.u32(crc); out.u32(bytes.size.toLong()); out.u32(bytes.size.toLong()); out.u16(nameBytes.size); out.u16(0)
            out.write(nameBytes)
            val dataOffset = out.count
            out.write(bytes)
            entries += Entry(name, bytes.size, crc, start, dataOffset)
        }
        fun finish(): Long {
            val centralStart = out.count
            entries.forEach { entry ->
                val name = entry.name.toByteArray(Charsets.US_ASCII)
                out.u32(0x02014b50); out.u16(20); out.u16(20); out.u16(entry.flags); out.u16(0)
                out.u16(entry.time); out.u16(entry.date); out.u32(entry.crc)
                out.u32(entry.size.toLong()); out.u32(entry.size.toLong()); out.u16(name.size)
                out.u16(0); out.u16(0); out.u16(0); out.u16(0); out.u32(0); out.u32(entry.localOffset)
                out.write(name)
            }
            val centralSize = out.count - centralStart
            out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(entries.size); out.u16(entries.size)
            out.u32(centralSize); out.u32(centralStart); out.u16(0)
            out.flush()
            return out.count
        }
    }
    fun inspect(file: File, limits: ArchiveLimits): Map<String, Entry> = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        if (length !in 22..limits.maxArchiveBytes) fail(ArchiveError.LIMIT_EXCEEDED)
        input.seek(length - 22)
        if (input.u32() != 0x06054b50L || input.u16() != 0 || input.u16() != 0) fail()
        val diskEntries = input.u16(); val count = input.u16()
        if (count !in 1..limits.maxEntries || diskEntries != count) fail()
        val centralSize = input.u32(); val centralOffset = input.u32()
        if (input.u16() != 0 || centralOffset + centralSize != length - 22) fail()
        val end = length - 22
        input.seek(centralOffset)
        val entries = linkedMapOf<String, Entry>()
        repeat(count) {
            if (input.filePointer + 46 > end || input.u32() != 0x02014b50L) fail()
            val madeBy = input.u16(); val needed = input.u16(); val flags = input.u16(); val method = input.u16()
            if (madeBy != 20 || needed !in 10..20 || flags !in setOf(0, 0x800) || method != 0) fail()
            val time = input.u16(); val date = input.u16(); val crc = input.u32()
            val compressed = input.u32(); val size = input.u32()
            val nameLength = input.u16(); val extraLength = input.u16(); val commentLength = input.u16()
            val disk = input.u16(); val internalAttributes = input.u16(); val externalAttributes = input.u32(); val local = input.u32()
            if (compressed != size || size > limits.maxArtworkBytes.coerceAtLeast(limits.maxManifestBytes) || nameLength !in 1..100 ||
                extraLength != 0 || commentLength != 0 || disk != 0 || internalAttributes != 0 || externalAttributes != 0L ||
                local >= centralOffset || input.filePointer + nameLength > end) fail()
            val name = ByteArray(nameLength).also(input::readFully).toString(Charsets.US_ASCII)
            validName(name)
            if (entries.containsKey(name)) fail()
            entries[name] = Entry(name, size.toInt(), crc, local, 0, flags, time, date, needed)
        }
        if (input.filePointer != end) fail()
        var nextOffset = 0L
        val localEntries = linkedMapOf<String, Entry>()
        for (entry in entries.values.sortedBy { it.localOffset }) {
            if (entry.localOffset != nextOffset || entry.localOffset + 30 > centralOffset) fail()
            input.seek(entry.localOffset)
            if (input.u32() != 0x04034b50L || input.u16() != entry.neededVersion || input.u16() != entry.flags || input.u16() != 0 ||
                input.u16() != entry.time || input.u16() != entry.date || input.u32() != entry.crc ||
                input.u32() != entry.size.toLong() || input.u32() != entry.size.toLong()) fail()
            val nameLength = input.u16(); val extraLength = input.u16()
            if (nameLength != entry.name.length || extraLength != 0 || input.filePointer + nameLength > centralOffset) fail()
            val name = ByteArray(nameLength).also(input::readFully).toString(Charsets.US_ASCII)
            if (name != entry.name) fail()
            val data = input.filePointer
            nextOffset = data + entry.size
            if (nextOffset > centralOffset) fail()
            localEntries[name] = entry.copy(dataOffset = data)
        }
        if (nextOffset != centralOffset || localEntries.keys.firstOrNull() != "manifest.json") fail()
        if (localEntries.values.sumOf { it.size.toLong() } > limits.maxTotalBlobBytes + limits.maxManifestBytes) fail(ArchiveError.LIMIT_EXCEEDED)
        localEntries
    }
    fun read(file: File, entry: Entry, expectedSha256: String? = null): ByteArray {
        val bytes = ByteArray(entry.size)
        RandomAccessFile(file, "r").use { input -> input.seek(entry.dataOffset); input.readFully(bytes) }
        if (CRC32().apply { update(bytes) }.value != entry.crc) fail(ArchiveError.HASH_MISMATCH)
        if (expectedSha256 != null && sha256(bytes) != expectedSha256) fail(ArchiveError.HASH_MISMATCH)
        return bytes
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun validName(name: String) {
        if (name != "manifest.json" && !name.matches(Regex("blobs/[a-f0-9]{64}"))) fail()
    }
    private fun fail(code: ArchiveError = ArchiveError.INVALID_ARCHIVE): Nothing = throw ArchiveException(code)
    private fun RandomAccessFile.u16(): Int {
        val low = read(); val high = read(); if (low < 0 || high < 0) throw EOFException()
        return low or (high shl 8)
    }
    private fun RandomAccessFile.u32(): Long = u16().toLong() or (u16().toLong() shl 16)
    private fun OutputStream.u16(value: Int) { write(value and 255); write(value ushr 8 and 255) }
    private fun OutputStream.u32(value: Long) { u16((value and 65535).toInt()); u16((value ushr 16 and 65535).toInt()) }
    private fun OutputStream.u32(value: Int) = u32(value.toLong())
    private class LimitedOutput(private val target: OutputStream, private val maximum: Long) : OutputStream() {
        var count = 0L
            private set
        override fun write(value: Int) { ensure(1); target.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { ensure(length); target.write(bytes, offset, length); count += length }
        override fun flush() { target.flush() }
        private fun ensure(length: Int) { if (length < 0 || count + length > maximum) fail(ArchiveError.LIMIT_EXCEEDED) }
    }
}
