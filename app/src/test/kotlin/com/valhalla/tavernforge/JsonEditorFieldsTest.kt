package com.valhalla.tavernforge

import com.valhalla.tavernforge.format.CharacterCardEngine
import com.valhalla.tavernforge.format.ExportResult
import com.valhalla.tavernforge.format.ParseResult
import org.junit.Assert.*
import org.junit.Test

class JsonEditorFieldsTest {
    private val engine = CharacterCardEngine()

    @Test fun nativeEntryKeysUseExactEscapedPointersAndKeepOriginalBytes() {
        val original = "{\"entries\":{\"a/b~c\":{\"content\":\"Lore\",\"unknown\":1e+02}}}".encodeToByteArray()
        val doc = (engine.parse(original) as ParseResult.Success).document
        val fields = editorFields(doc).first
        val content = fields.single { it.label.endsWith("· Content") }
        assertEquals("/entries/a~1b~0c/content", content.path)
        assertEquals("Lore", content.preview)
        assertArrayEquals(original, (engine.export(doc) as ExportResult.Success).bytes)
    }

    @Test fun excessiveMemberPathsAreNotTruncatedIntoDifferentEditablePaths() {
        val key = "x".repeat(MAX_EDITOR_PATH_CHARS)
        val doc = (engine.parse("{\"entries\":{\"$key\":{\"content\":\"Lore\"}}}".encodeToByteArray()) as ParseResult.Success).document
        val (fields, limited) = editorFields(doc)
        assertTrue(limited)
        assertTrue(fields.none { it.path.startsWith("/entries/") })
    }

    @Test fun exportSuggestionCannotContainDirectorySeparatorsOrControlCharacters() {
        val name = exportFileName("/Downloads/unsafe\\draft\ncard.json")
        assertEquals("draft_card-edited.json", name)
        assertFalse(name.contains('/')); assertFalse(name.contains('\\')); assertFalse(name.contains('\n'))
    }
}
