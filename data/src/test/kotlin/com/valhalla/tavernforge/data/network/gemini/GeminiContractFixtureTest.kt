package com.valhalla.tavernforge.data.network.gemini

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class GeminiContractFixtureTest {
    @Test fun publicWireFactsRetainReviewedRevisionAndExactFieldNames() {
        val bytes = resource("wire-contract.json")
        assertEquals("4f4b4873d0baf9835f38bcc13425055e3fce202c78e3fb351a884c88cd1197ba", sha256(bytes))
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals("20261030", root["revision"]!!.jsonPrimitive.content)
        val schemas = root["schemas"]!!.jsonObject
        val model = schemas["Model"]!!.jsonObject["properties"]!!.jsonObject
        assertFalse(model.containsKey("outputModalities"))
        assertTrue(model.containsKey("supportedGenerationMethods"))
        val text = schemas["TextResponseFormat"]!!.jsonObject["properties"]!!.jsonObject
        assertTrue(text["mimeType"]!!.jsonObject["enum"]!!.jsonArray.contains(JsonPrimitive("APPLICATION_JSON")))
        assertTrue(text.containsKey("schema"))
    }

    @Test fun registryMatchesExactOfficialModelFactsAndFailsClosedForUnknownIds() {
        val bytes = resource("model-capabilities.json")
        assertEquals("89ddd4a8159b843db4b044a7711ec33c4f53906fb719491d3a0fc3a2a9379c89", sha256(bytes))
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(GeminiCapabilityRegistry.VERSION, root["registryVersion"]!!.jsonPrimitive.content)
        root["models"]!!.jsonArray.forEach { value ->
            val model = value.jsonObject
            val capability = GeminiCapabilityRegistry.resolve(model["name"]!!.jsonPrimitive.content, setOf("generateContent"))
            assertEquals(model["imageOutput"]!!.jsonPrimitive.boolean, capability.imageOutput)
            assertEquals(model["structuredOutput"]!!.jsonPrimitive.boolean, capability.structuredOutput)
            assertEquals(model["source"]!!.jsonPrimitive.content, capability.evidenceUrl)
        }
        assertEquals(GeminiCapabilities(), GeminiCapabilityRegistry.resolve("models/unknown-image", setOf("generateContent")))
        assertEquals(GeminiCapabilities(), GeminiCapabilityRegistry.resolve("models/gemini-3.8-flash", emptySet()))
    }

    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("gemini/$name")).use { it.readBytes() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
