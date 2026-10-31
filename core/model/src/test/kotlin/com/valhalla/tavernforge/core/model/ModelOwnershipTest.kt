package com.valhalla.tavernforge.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOwnershipTest {
    @Test
    fun sourceOwnsItsInputAndEachReturnedByteArray() {
        val caller = "abc".encodeToByteArray()
        val source = SourceEnvelope(caller)
        caller[0] = 'x'.code.toByte()
        source.bytes()[1] = 'y'.code.toByte()
        assertArrayEquals("abc".encodeToByteArray(), source.bytes())
        assertEquals(3, source.sizeBytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", source.sha256)
    }

    @Test
    fun validationReportCannotBeChangedByMutatingItsInputList() {
        val original = mutableListOf(Diagnostic("test.error", "Invalid card"))
        val report = ValidationReport(original)
        original.clear()
        assertFalse(report.isValid)
        assertEquals(1, report.issues.size)
    }

    @Test
    fun warningsAndInfoDoNotBecomeFormatErrors() {
        val report = ValidationReport(listOf(
            Diagnostic("test.warning", "Unknown extension", severity = Severity.WARNING),
            Diagnostic("test.info", "Add a description", severity = Severity.INFO),
        ))
        assertTrue(report.isValid)
    }

    @Test
    fun invalidResourceBudgetsFailAtConfigurationBoundary() {
        val invalid = listOf<() -> JsonLimits>(
            { JsonLimits(maxBytes = 0) },
            { JsonLimits(maxDepth = 0) },
            { JsonLimits(maxStringChars = -1) },
            { JsonLimits(maxNodes = 0) },
            { JsonLimits(maxCollectionEntries = -1) },
            { JsonLimits(maxNumberChars = 0) },
            { JsonLimits(maxPatchOperations = -1) },
            { JsonLimits(maxPatchWorkBytes = 0) },
        )
        invalid.forEach { create ->
            try {
                create()
                throw AssertionError("Invalid budget was accepted")
            } catch (_: IllegalArgumentException) {
                // No parser call should be needed to discover an invalid policy.
            }
        }
    }
}
