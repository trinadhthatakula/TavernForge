package com.valhalla.tavernforge

import com.valhalla.tavernforge.core.model.Diagnostic
import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.CharacterDocument
import com.valhalla.tavernforge.format.JsonPatch
import com.valhalla.tavernforge.format.PatchResult

/** History covers unsaved commands against one immutable saved revision. No inverse serialization. */
internal class EditHistory(
    private val engine: CharacterCardEngine,
    private val base: CharacterDocument,
    private val maxCommands: Int = 100,
    private val maxBytes: Long = 16L * 1024 * 1024,
) {
    private data class Command(val patches: List<JsonPatch>, val size: Long)
    private var commands = emptyList<Command>()
    private var cursor = 0
    val canUndo get() = cursor > 0
    val canRedo get() = cursor < commands.size
    val patches get() = commands.take(cursor).flatMap { it.patches }

    fun append(patches: List<JsonPatch>): PatchResult {
        val size = patches.sumOf { patch -> patch.path.encodeToByteArray().size.toLong() + when (patch) {
            is JsonPatch.Add -> patch.value.toString().encodeToByteArray().size.toLong()
            is JsonPatch.Replace -> patch.value.toString().encodeToByteArray().size.toLong()
            is JsonPatch.Remove -> 0L
        } }
        val kept = commands.take(cursor)
        if (kept.size >= maxCommands || kept.sumOf { it.size } + size > maxBytes)
            return PatchResult.Failure(listOf(Diagnostic("HISTORY_LIMIT", "Save or discard changes to continue. This command was not applied.")))
        val proposed = kept + Command(patches.toList(), size)
        return replay(proposed.flatMap { it.patches }).also { result ->
            if (result is PatchResult.Success) { commands = proposed; cursor = proposed.size }
        }
    }
    fun undo(): PatchResult = move((cursor - 1).coerceAtLeast(0))
    fun redo(): PatchResult = move((cursor + 1).coerceAtMost(commands.size))
    private fun move(next: Int): PatchResult = replay(commands.take(next).flatMap { it.patches }).also {
        if (it is PatchResult.Success) cursor = next
    }
    private fun replay(patches: List<JsonPatch>): PatchResult = if (patches.isEmpty()) PatchResult.Success(base)
        else engine.patch(base, base.revision, patches)
}
