package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.crypto.model.IdentityKeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CryptoSafetyNumberTest {
    @Test
    fun `generates exact 60 numeric digits grouped in 12 groups of 5`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()

        val formatted = CryptoSafetyNumber.generate(alice.publicKey, bob.publicKey)
        val rawDigits = CryptoSafetyNumber.generateDigits(alice.publicKey, bob.publicKey)

        val groups = formatted.split(" ")
        assertEquals(CryptoConstants.SAFETY_NUMBER_GROUPS, groups.size)
        assertTrue(groups.all { it.length == CryptoConstants.SAFETY_NUMBER_GROUP_DIGITS && it.all(Char::isDigit) })
        assertEquals(CryptoConstants.SAFETY_NUMBER_DIGITS, rawDigits.length)
        assertTrue(rawDigits.all(Char::isDigit))
        assertEquals(rawDigits, formatted.replace(" ", ""))

        alice.zeroize()
        bob.zeroize()
    }

    @Test
    fun `is deterministic and symmetric regardless of Alice or Bob order`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()

        val aliceViewFirst = CryptoSafetyNumber.generate(alice.publicKey, bob.publicKey)
        val aliceViewSecond = CryptoSafetyNumber.generate(alice.publicKey, bob.publicKey)
        val bobView = CryptoSafetyNumber.generate(bob.publicKey, alice.publicKey)

        assertEquals(aliceViewFirst, aliceViewSecond)
        assertEquals(aliceViewFirst, bobView)

        val qrAlice = CryptoSafetyNumber.generateQrPayload(alice.publicKey, bob.publicKey)
        val qrBob = CryptoSafetyNumber.generateQrPayload(bob.publicKey, alice.publicKey)
        assertEquals(qrAlice, qrBob)
        assertTrue(qrAlice.contains(CryptoSafetyNumber.generateDigits(alice.publicKey, bob.publicKey)))

        alice.zeroize()
        bob.zeroize()
    }

    @Test
    fun `produces distinct safety numbers and QR payloads for distinct keys`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val charlie = IdentityKeyPair.generate()

        val aliceBob = CryptoSafetyNumber.generate(alice.publicKey, bob.publicKey)
        val aliceCharlie = CryptoSafetyNumber.generate(alice.publicKey, charlie.publicKey)
        assertNotEquals(aliceBob, aliceCharlie)

        val qrAliceBob = CryptoSafetyNumber.generateQrPayload(alice.publicKey, bob.publicKey)
        val qrAliceCharlie = CryptoSafetyNumber.generateQrPayload(alice.publicKey, charlie.publicKey)
        assertNotEquals(qrAliceBob, qrAliceCharlie)

        alice.zeroize()
        bob.zeroize()
        charlie.zeroize()
    }
}
