package com.ninerouter.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {
    @Test
    fun translatesExactEntriesAndFallsBackToEnglish() {
        val translator = Translator.fromMap("id", mapOf("Settings" to "Pengaturan"))

        assertEquals("Pengaturan", translator.translate("Settings"))
        assertEquals("Not translated", translator.translate("Not translated"))
    }

    @Test
    fun rendersDynamicTemplateUsingPlaceholderIndexes() {
        val translator = Translator.fromMap(
            "id",
            mapOf("Connected {0} in {1} ms" to "Dalam {1} ms, terhubung ke {0}"),
        )

        assertEquals("Dalam 12 ms, terhubung ke Provider X", translator.translate("Connected Provider X in 12 ms"))
    }

    @Test
    fun recognizesRightToLeftLocales() {
        assertTrue(Translator.fromMap("ar", emptyMap()).isRtl)
        assertFalse(Translator.fromMap("id", emptyMap()).isRtl)
    }
}
