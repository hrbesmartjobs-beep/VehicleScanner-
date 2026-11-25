package com.vehiclescanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateNormalizerTest {

    private fun norm(s: String) = PlateNormalizer.normalize(s)

    @Test fun formatsFromSpec() {
        assertEquals("KL08AB1234", norm("KL-08-AB-1234").normalized)
        assertEquals("KL08AB1234", norm("KL 08 AB 1234").normalized)
        assertEquals("TN09CD5678", norm("tn-09-cd-5678").normalized)
        assertEquals("KA01EF1234", norm("KA-01-EF-1234").normalized)
        assertEquals("MH12XY9876", norm("MH.12.XY.9876").normalized)
        listOf("KL-08-AB-1234", "TN-09-CD-5678").forEach { assertEquals(0, norm(it).corrections) }
    }

    @Test fun bharatSeriesAndDelhi() {
        assertTrue(norm("22 BH 1234 AA").valid)
        assertEquals("22BH1234AA", norm("22-BH-1234-AA").normalized)
        assertTrue(norm("DL 3C AB 1234").valid)
        assertTrue(norm("KA 01 1234").valid)        // old plate without series letters
    }

    @Test fun positionAwareOcrCorrection() {
        // O read instead of 0 in district, 1 read as I in number
        val r = norm("KL O8 AB I234")
        assertTrue(r.valid)
        assertEquals("KL08AB1234", r.normalized)
        assertEquals(2, r.corrections)
        // 8 read instead of B in the series
        val r2 = norm("MH12X89876")
        assertEquals("MH12XB9876", r2.normalized)
        assertTrue(r2.corrections >= 1)
    }

    @Test fun stripsIndAndBorderJunk() {
        assertEquals("KL08AB1234", norm("IND KL08AB1234").normalized)
        assertEquals("TN09CD5678", norm("|TN09CD5678.").normalized)
    }

    @Test fun invalidStaysInvalid() {
        assertFalse(norm("HELLO").valid)
        assertFalse(norm("").valid)
        assertFalse(PlateNormalizer.isValid("ZZ08AB1234"))   // not a state code
        // Only the top line of a two-line bike plate is NOT a complete number.
        assertFalse(norm("KL 57").valid)
        assertFalse(norm("KL 5 7").valid)
        assertTrue(norm("KL 57 0884").valid)
    }

    @Test fun prettyFormat() {
        assertEquals("KL 08 AB 1234", PlateNormalizer.format("KL08AB1234"))
        assertEquals("22 BH 1234 AA", PlateNormalizer.format("22BH1234AA"))
    }

    @org.junit.Test fun guessedStateCodeIsUncertain() {
        // First letter "K" cut off: "L08BU6836" must not silently become a confident reading.
        val r = norm("L08BU6836")
        if (r.valid) assertTrue(r.uncertain || r.corrections > 0)
        assertFalse(norm("KL08BU6836").uncertain)
        assertTrue(norm("KL08BU6836").valid)
    }
}
