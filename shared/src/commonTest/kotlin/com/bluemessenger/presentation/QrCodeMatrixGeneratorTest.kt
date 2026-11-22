package com.bluemessenger.presentation

import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.presentation.chat.QrCodeMatrixGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QrCodeMatrixGeneratorTest {
    @Test
    fun `encodes 60-digit safety number into valid square QR matrix with dark and light modules`() {
        val safetyNumber = "123456789012345678901234567890123456789012345678901234567890"
        val matrix = QrCodeMatrixGenerator.encode(safetyNumber).getOrThrow()

        assertTrue(matrix.size > 0)
        assertEquals(matrix.size * matrix.size, matrix.darkModules.size)
        assertTrue(matrix.darkModules.any { it })
        assertTrue(matrix.darkModules.any { !it })
        assertEquals(matrix.darkModules.first(), matrix.isDark(0, 0))
    }

    @Test
    fun `fails fast with InvalidInputError on blank QR content`() {
        val emptyResult = QrCodeMatrixGenerator.encode("")
        assertIs<InvalidInputError>(emptyResult.exceptionOrNull()?.toDomainErrorOrNull())

        val blankResult = QrCodeMatrixGenerator.encode("   \n\t  ")
        assertIs<InvalidInputError>(blankResult.exceptionOrNull()?.toDomainErrorOrNull())
    }
}
