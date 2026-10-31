package com.valhalla.tavernforge.data

import com.valhalla.tavernforge.core.model.SourceEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Synthetic JSON with deliberate whitespace, numeric lexemes and unknown values. */
internal val preservedV1 = """
    {
      "name": "Archive Keeper", "description": "", "personality": "",
      "scenario": "", "first_mes": "", "mes_example": "",
      "unknown": {"null": null, "false": false, "number": 1.2300e+09,
                  "large": 123456789012345678901234567890, "negativeZero": -0}
    }
""".trimIndent().plus("\n").encodeToByteArray()

internal fun source(bytes: ByteArray = preservedV1) = SourceEnvelope(bytes)

/** Models the index contract only; these tests do not pretend this in-memory implementation is Room. */
internal class FaultIndex : ProjectIndex {
    val records = linkedMapOf<String, ProjectRecord>()
    private val changes = MutableStateFlow<List<ProjectRecord>>(emptyList())
    var beforeAll: suspend () -> Unit = {}
    var beforeFind: suspend (String) -> Unit = {}
    var beforeInsert: suspend (ProjectRecord) -> Unit = {}
    var afterInsert: suspend (ProjectRecord) -> Unit = {}
    var beforeRename: suspend () -> Unit = {}
    var beforeDelete: suspend () -> Unit = {}
    var beforeUpdateContent: suspend () -> Unit = {}
    var afterUpdateContent: suspend () -> Unit = {}
    var allCalls = 0
    var insertCalls = 0
    var updateContentCalls = 0

    private fun ordered() = records.values.sortedWith(compareByDescending<ProjectRecord> { it.summary.updatedAtEpochMillis }.thenBy { it.summary.id })
    private fun emit() { changes.value = ordered() }
    override fun observe(): Flow<List<ProjectRecord>> = changes
    override suspend fun all(): List<ProjectRecord> { allCalls++; beforeAll(); return ordered() }
    override suspend fun find(id: String): ProjectRecord? { beforeFind(id); return records[id] }
    override suspend fun insert(record: ProjectRecord) {
        insertCalls++
        beforeInsert(record)
        check(record.summary.id !in records) { "Duplicate test index ID" }
        records[record.summary.id] = record
        emit()
        afterInsert(record)
    }
    override suspend fun rename(id: String, title: String, updatedAtEpochMillis: Long): Int {
        beforeRename()
        val old = records[id] ?: return 0
        records[id] = old.copy(summary = old.summary.copy(title = title, updatedAtEpochMillis = updatedAtEpochMillis))
        emit()
        return 1
    }
    override suspend fun delete(id: String): Int {
        beforeDelete()
        val deleted = records.remove(id) != null
        emit()
        return if (deleted) 1 else 0
    }

    override suspend fun updateContent(
        id: String,
        expectedRevision: Long,
        currentFileKey: String,
        currentSha256: String,
        currentSizeBytes: Int,
        isDraft: Boolean,
        updatedAtEpochMillis: Long,
    ): Int {
        updateContentCalls++
        beforeUpdateContent()
        val old = records[id] ?: return 0
        if (old.documentRevision != expectedRevision) return 0
        records[id] = old.copy(
            summary = old.summary.copy(isDraft = isDraft, updatedAtEpochMillis = updatedAtEpochMillis),
            documentRevision = expectedRevision + 1,
            currentFileKey = currentFileKey,
            currentSha256 = currentSha256,
            currentSizeBytes = currentSizeBytes,
        )
        emit()
        afterUpdateContent()
        return 1
    }
}

internal class FaultFiles(private val delegate: ProjectFileStore) : ProjectFileStore {
    var beforeEnsure: () -> Unit = {}
    var beforeRead: () -> Unit = {}
    var beforePrune: () -> Unit = {}
    var ensured = 0
    val prunedReferences = mutableListOf<Set<String>>()
    override fun ensureSource(source: SourceEnvelope): String { ensured++; beforeEnsure(); return delegate.ensureSource(source) }
    override fun read(fileKey: String, expectedSha256: String, expectedSizeBytes: Int): SourceEnvelope {
        beforeRead()
        return delegate.read(fileKey, expectedSha256, expectedSizeBytes)
    }
    override fun prune(referenced: Set<String>) { beforePrune(); prunedReferences += referenced.toSet(); delegate.prune(referenced) }
}
