package com.valhalla.tavernforge

import com.valhalla.tavernforge.core.model.SourceFormat

/** Informational receiver findings, separate from specification validity and strict export policy. */
internal fun receiverExportGuidance(format: SourceFormat, characterName: String?): List<String> = buildList {
    if (format in setOf(SourceFormat.V1, SourceFormat.V2, SourceFormat.V3) && characterName.isNullOrBlank()) {
        add("A nonempty character name is recommended for SillyTavern import. Empty names are format-valid, but SillyTavern 1.19.0 did not import them reliably in our checks.")
    }
    when (format) {
        SourceFormat.LOREBOOK_V2 -> add("This is a V2 CharacterBook, not native World Info. SillyTavern 1.19.0 stores its entries without converting their fields. Embed it in a V2 character, or use a tool with explicit conversion support.")
        SourceFormat.LOREBOOK_V3 -> add("SillyTavern 1.19.0 rejects this standalone V3 lorebook in its World Info importer. You can embed it in a V3 character or use a receiver that supports this format.")
        SourceFormat.V1, SourceFormat.V2 -> add("SillyTavern 1.19.0 rewrites these cards as V3 when it exports them. Keep this original file if source-version fidelity matters.")
        else -> Unit
    }
}
