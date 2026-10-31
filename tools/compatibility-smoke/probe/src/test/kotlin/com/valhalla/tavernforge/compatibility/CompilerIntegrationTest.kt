package com.valhalla.tavernforge.compatibility

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication

class CompilerIntegrationTest {
    @Test
    fun koinCompilerTransformsConstructorResolution() {
        val application = koinApplication { modules(probeModule()) }
        try {
            val service = application.koin.get<ProbeService>()
            assertSame(service, application.koin.get<ProbeConsumer>().service)
        } finally {
            application.close()
        }
    }

    @Test
    fun serializationCompilerProducesWorkingSerializer() {
        val payload = ProbePayload("compiler integration")
        val encoded = Json.encodeToString(ProbePayload.serializer(), payload)
        assertEquals(payload, Json.decodeFromString(ProbePayload.serializer(), encoded))
    }

    @Test
    fun roomGeneratedDatabaseImplementationIsCompiled() {
        val implementation = Class.forName(
            "com.valhalla.tavernforge.compatibility.ProbeDatabase_Impl",
            false,
            javaClass.classLoader,
        )
        assertTrue(ProbeDatabase::class.java.isAssignableFrom(implementation))
    }
}
