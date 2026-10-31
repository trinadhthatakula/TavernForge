package com.valhalla.tavernforge.format

import com.valhalla.tavernforge.core.model.JsonLimits
import com.valhalla.tavernforge.core.model.ValidationPhase
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedJsonParsingTest {
    private val engine = CharacterCardEngine()

    @Test
    fun byteBudgetIncludesWhitespaceAndAcceptsTheExactBoundary() {
        val bytes = " {\"a\":1} \n".encodeToByteArray()
        CharacterCardEngine(JsonLimits(maxBytes = bytes.size)).read(bytes)
        CharacterCardEngine(JsonLimits(maxBytes = bytes.size + 1)).read(bytes)
        val failure = assertParseFailure(CharacterCardEngine(JsonLimits(maxBytes = bytes.size - 1)), bytes)
        assertNull("An oversized source must not be copied into a failure object", failure.source)
    }

    @Test
    fun nestingBudgetCountsTheRootAndEachContainedValue() {
        val json = "{\"a\":{\"b\":0}}"
        CharacterCardEngine(JsonLimits(maxDepth = 3)).read(json)
        assertParseFailure(CharacterCardEngine(JsonLimits(maxDepth = 2)), json)
        CharacterCardEngine(JsonLimits(maxDepth = 1)).read("{}")
        assertParseFailure(CharacterCardEngine(JsonLimits(maxDepth = 1)), "{\"a\":0}")
    }

    @Test
    fun decodedStringBudgetAppliesToValuesAndKeys() {
        val constrained = CharacterCardEngine(JsonLimits(maxStringChars = 2))
        constrained.read("""{"ab":"\u0061b"}""")
        assertParseFailure(constrained, """{"abc":"a"}""")
        assertParseFailure(constrained, """{"a":"\u0061bc"}""")
        CharacterCardEngine(JsonLimits(maxStringChars = 0)).read("""{"":""}""")
    }

    @Test
    fun surrogatePairCountsAsTwoUtf16Units() {
        val json = """{"a":"\uD83D\uDE80"}"""
        CharacterCardEngine(JsonLimits(maxStringChars = 2)).read(json)
        assertParseFailure(CharacterCardEngine(JsonLimits(maxStringChars = 1)), json)
    }

    @Test
    fun nodeBudgetCountsContainersAndValuesButNotMemberNames() {
        val json = """{"a":[0,null],"b":true}"""
        CharacterCardEngine(JsonLimits(maxNodes = 5)).read(json)
        assertParseFailure(CharacterCardEngine(JsonLimits(maxNodes = 4)), json)
        CharacterCardEngine(JsonLimits(maxNodes = 1)).read("{}")
    }

    @Test
    fun collectionBudgetBoundsEachObjectAndArray() {
        val constrained = CharacterCardEngine(JsonLimits(maxCollectionEntries = 2))
        constrained.read("""{"a":[0,1],"b":{}}""")
        assertParseFailure(constrained, """{"a":0,"b":1,"c":2}""")
        assertParseFailure(constrained, """{"a":[0,1,2]}""")
        CharacterCardEngine(JsonLimits(maxCollectionEntries = 0)).read("{}")
    }

    @Test
    fun numberTokenBudgetPreventsUnboundedNumericLexemes() {
        CharacterCardEngine(JsonLimits(maxNumberChars = 5)).read("""{"n":1e+10}""")
        assertParseFailure(CharacterCardEngine(JsonLimits(maxNumberChars = 4)), """{"n":1e+10}""")
    }

    @Test
    fun malformedUtf8FailsWithoutReplacementDecodingAndRetainsOriginalSource() {
        val cases = listOf(
            byteArrayOf(0x7b,0x22,0x61,0x22,0x3a,0x22,0xc3.toByte(),0x28,0x22,0x7d),
            byteArrayOf(0x7b,0x22,0x61,0x22,0x3a,0x22,0xed.toByte(),0xa0.toByte(),0x80.toByte(),0x22,0x7d),
            byteArrayOf(0x7b,0x22,0x61,0x22,0x3a,0x22,0xf0.toByte(),0x9f.toByte()),
        )
        cases.forEach { bytes -> assertArrayEquals(bytes, assertParseFailure(engine, bytes).source!!.bytes()) }
    }

    @Test
    fun duplicateDecodedObjectNamesAreRejectedAtAnyLevel() {
        listOf(
            """{"a":1,"a":2}""",
            """{"a":1,"\u0061":2}""",
            """{"outer":{"x/y":null,"x\u002Fy":false}}""",
            """{"outer":[{"same":0,"same":1}]}""",
        ).forEach { json ->
            val failure = assertParseFailure(engine, json)
            assertArrayEquals(json.encodeToByteArray(), failure.source!!.bytes())
        }
    }

    @Test
    fun identicalNamesInSeparateObjectsAreAllowed() {
        engine.read("""{"a":{"key":1},"b":{"key":2}}""")
    }

    @Test
    fun malformedNumberTokensAreNeverAcceptedAsJson() {
        listOf("01", "+1", ".1", "1.", "1e", "1e+", "--1", "NaN", "Infinity", "-Infinity").forEach { token ->
            assertParseFailure(engine, "{\"n\":$token}")
        }
    }

    @Test
    fun unpairedSurrogatesAndInvalidEscapesAreRejected() {
        listOf(
            """{"s":"\uD800"}""",
            """{"s":"\uDC00"}""",
            """{"s":"\uD800x"}""",
            """{"s":"\uD800\u0041"}""",
            """{"s":"\x41"}""",
            """{"s":"\uQQQQ"}""",
            """{"s":"\u００４１"}""",
            """{"s":"\u٠٠٤١"}""",
            "{\"s\":\"line\nfeed\"}",
        ).forEach { assertParseFailure(engine, it) }
    }

    @Test
    fun validUnicodeAndEscapesSurviveDecoding() {
        val document = engine.read("""{"s":"\uD83D\uDE80 naïve తెలుగు \" \\ \/ \b \f \n \r \t"}""")
        assertEquals("🚀 naïve తెలుగు \" \\ / \b \u000c \n \r \t", (document.raw["s"] as JsonPrimitive).content)
    }

    @Test
    fun structuralMalformedJsonAndTrailingTokensFail() {
        listOf("", " ", "{", "{\"a\":}", "{\"a\":1,}", "{\"a\":[1,]}", "{} {}", "{}null", "{/*comment*/}", "{a:1}").forEach {
            assertParseFailure(engine, it)
        }
    }

    @Test
    fun nonObjectRootsDoNotBecomeCharacterDocuments() {
        listOf("[]", "null", "true", "1", "\"card\"").forEach { json ->
            val failure = assertParseFailure(engine, json)
            assertNotNull(failure.source)
        }
    }

    @Test
    fun malformedSmallSourceRemainsAvailableAndDiagnosticsIdentifyParsePhase() {
        val bytes = "{\"name\": }".encodeToByteArray()
        val failure = assertParseFailure(engine, bytes)
        bytes[0] = 0
        assertArrayEquals("{\"name\": }".encodeToByteArray(), failure.source!!.bytes())
        assertTrue(failure.issues.any { it.phase == ValidationPhase.PARSE })
    }

    @Test
    fun errorUnderLongEscapedAncestorMaterializesOneExactPointer() {
        val key = "long/~".repeat(1024)
        val json = "{\"$key\":{\"duplicate\":1,\"duplicate\":2}}"
        val failure = assertParseFailure(engine, json)
        val expected = "/" + key.replace("~", "~0").replace("/", "~1") + "/duplicate"
        assertEquals(expected, failure.issues.first { it.code == "DUPLICATE_KEY" }.path)
        assertArrayEquals(json.encodeToByteArray(), failure.source!!.bytes())
    }
}
