package com.yaoyihan.nikonconnect

import org.junit.Assert.*
import org.junit.Test

class UiLoadingPolicyTest {
    @Test fun placeholderRatioIsBoundedAndHandlesUnknownDimensions() {
        assertEquals(.8f, mediaCardRatio(0, 0), .001f)
        assertEquals(.8f, mediaCardRatio(-1, 100), .001f)
        assertEquals(.65f, mediaCardRatio(100, 1000), .001f)
        assertEquals(1.6f, mediaCardRatio(1000, 100), .001f)
        assertEquals(.75f, mediaCardRatio(300, 400), .001f)
    }

    @Test fun providerErrorsAreAttachedToTheCorrectFields() {
        val valid = AiProviderProfile("test", "Test")
        assertTrue(aiProviderFieldErrors(valid).isEmpty())
        assertEquals(setOf("name", "endpoint", "key"), aiProviderFieldErrors(valid.copy(
            name = " ", endpoint = "http://example.com", key = "invalid key")).keys)
        assertEquals(setOf("endpoint"), aiProviderFieldErrors(valid.copy(
            endpoint = "https://example.com/generate?key=secret")).keys)
        // A blank key can be saved as an unfinished profile, as before.
        assertTrue(aiProviderFieldErrors(valid.copy(key = "")).isEmpty())
    }
}
