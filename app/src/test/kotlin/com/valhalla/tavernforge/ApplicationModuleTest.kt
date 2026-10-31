package com.valhalla.tavernforge

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication

class ApplicationModuleTest {
    @Test
    fun applicationGraphResolvesSharedFormatEngine() {
        val application = koinApplication { modules(applicationModule()) }
        try {
            val services = application.koin.get<FormatServices>()
            assertSame(services, application.koin.get<FormatServices>())
            assertTrue(services.engine.validateV2(services.engine.createV2()).isValid)
        } finally {
            application.close()
        }
    }
}
