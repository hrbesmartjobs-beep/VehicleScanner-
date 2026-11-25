package com.vehiclescanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherAndGeometryTest {

    private val matcher = VehicleMatcher(listOf("KL08AB1234", "TN09CD5678", "MH12XY9876"))

    @Test fun exactMatchHighConfidence() {
        val d = matcher.decide(PlateNormalizer.normalize("KL 08 AB 1234"), 0.95f, 0.75f)
        assertEquals(PlateStatus.MATCH, d.status)
    }

    @Test fun notFound() {
        val d = matcher.decide(PlateNormalizer.normalize("KA 01 EF 9999"), 0.95f, 0.75f)
        assertEquals(PlateStatus.NOT_FOUND, d.status)
    }

    @Test fun lowConfidenceIsNeverAutoMatched() {
        val d = matcher.decide(PlateNormalizer.normalize("KL08AB1234"), 0.50f, 0.75f)
        assertEquals(PlateStatus.REVIEW, d.status)
        assertEquals("KL08AB1234", d.matchedNumber)
    }

    @Test fun nearMissGoesToReview() {
        val d = matcher.decide(PlateNormalizer.normalize("TN09CD5679"), 0.95f, 0.75f)
        assertEquals(PlateStatus.REVIEW, d.status)
        assertEquals("TN09CD5678", d.matchedNumber)
    }

    @Test fun correctionsLowerConfidence() {
        val d = matcher.decide(PlateNormalizer.normalize("KL O8 AB I234"), 0.85f, 0.75f)
        // 2 corrections × 0.08 = 0.69 < 0.75 → review, with the DB number suggested
        assertEquals(PlateStatus.REVIEW, d.status)
        assertEquals("KL08AB1234", d.matchedNumber)
    }

    @Test fun ocrMisreadOfSavedNumberIsReviewNotNotFound() {
        // Real case: dotted font read "KL08BU6836" as "KL0SBU836" (8→S and a dropped 6).
        val m = VehicleMatcher(listOf("KL08BU6836", "KL08BX1055", "KL12K9033", "KL57L0981"))
        val d = m.decide(PlateNormalizer.normalize("KL0SBU836"), 0.95f, 0.75f)
        assertEquals(PlateStatus.REVIEW, d.status)
        assertEquals("KL08BU6836", d.matchedNumber)
        // Two unrelated wrong characters are a different vehicle.
        assertEquals(PlateStatus.NOT_FOUND, m.decide(PlateNormalizer.normalize("KL08BU6911"), 0.95f, 0.75f).status)
    }

    @Test fun districtAndSerialPaddingStillMatch() {
        val m = VehicleMatcher(listOf("KL7A1234", "KL07B0123"))
        assertEquals(PlateStatus.MATCH, m.decide(PlateNormalizer.normalize("KL 07 A 1234"), 0.95f, 0.75f).status)
        assertEquals("KL7A1234", m.lookup("KL07A1234"))
        assertEquals("KL07B0123", m.lookup("KL7B123"))
    }

    @Test fun editDistance() {
        assertEquals(0, VehicleMatcher.editDistanceAtMostOne("AB", "AB"))
        assertEquals(1, VehicleMatcher.editDistanceAtMostOne("ABC", "ABD"))
        assertEquals(1, VehicleMatcher.editDistanceAtMostOne("ABC", "ABXC"))
        assertEquals(-1, VehicleMatcher.editDistanceAtMostOne("ABC", "AXY"))
        assertNull(matcher.nearest("DL01ZZ0000"))
    }

    @Test fun nmsRemovesDuplicatesAndNested() {
        val boxes = listOf(
            Box(0f, 0f, 100f, 30f, 0.9f),
            Box(2f, 1f, 101f, 31f, 0.8f),     // duplicate
            Box(10f, 5f, 60f, 25f, 0.7f),     // nested in the first
            Box(300f, 300f, 400f, 330f, 0.6f) // separate plate
        )
        val kept = Nms.apply(boxes)
        assertEquals(2, kept.size)
        assertEquals(0.9f, kept[0].score)
    }

    @Test fun tilerCoversImage() {
        assertEquals(1, Tiler.tiles(1000, 800).size)
        val t = Tiler.tiles(4000, 3000, 1280, 0.25f)
        assertTrue(t.size > 4)
        val tiles = t.drop(1)
        assertTrue(tiles.any { it.x + it.w == 4000 })
        assertTrue(tiles.any { it.y + it.h == 3000 })
        assertTrue(tiles.all { it.x >= 0 && it.y >= 0 && it.x + it.w <= 4000 && it.y + it.h <= 3000 })
    }

    @Test fun letterboxRoundTrip() {
        val lb = Letterbox.of(1280, 720, 640)
        val b = Box(100f, 50f, 300f, 120f)
        val mapped = Box(b.left * lb.scale + lb.padX, b.top * lb.scale + lb.padY, b.right * lb.scale + lb.padX, b.bottom * lb.scale + lb.padY)
        val back = lb.unmap(mapped)
        assertEquals(100f, back.left, 0.01f); assertEquals(120f, back.bottom, 0.01f)
    }

    @Test fun yoloV8Decode() {
        // [1, 5, 10]: 10 anchors, one class, normalised coords
        val n = 10
        val out = FloatArray(5 * n)
        fun set(row: Int, i: Int, v: Float) { out[row * n + i] = v }
        set(0, 1, 0.5f); set(1, 1, 0.5f); set(2, 1, 0.25f); set(3, 1, 0.1f); set(4, 1, 0.9f)
        set(4, 0, 0.1f); set(4, 2, 0.2f)
        val boxes = YoloDecoder.decode(out, intArrayOf(1, 5, n), 640, 0.25f)
        assertEquals(1, boxes.size)
        assertEquals(240f, boxes[0].left, 0.01f)
        assertEquals(400f, boxes[0].right, 0.01f)
    }

    @Test fun yoloEndToEndDecode() {
        val out = floatArrayOf(10f, 20f, 110f, 50f, 0.8f, 0f, 0f, 0f, 1f, 1f, 0.1f, 0f)
        val boxes = YoloDecoder.decode(out, intArrayOf(1, 2, 6), 640, 0.25f)
        assertEquals(YoloDecoder.Layout.END_TO_END, YoloDecoder.detectLayout(intArrayOf(1, 300, 6)))
        assertEquals(1, boxes.size)
        assertEquals(110f, boxes[0].right, 0.01f)
    }

    @Test fun twoLinePlateAssembly() {
        val lines = listOf(
            OcrLine("AB 1234", Box(0f, 40f, 100f, 75f), 0.9f),
            OcrLine("KL 08", Box(10f, 0f, 90f, 35f), 0.92f)
        )
        val r = PlateTextAssembler.assemble(lines, "gray")!!
        assertEquals("KL08AB1234", r.norm.normalized)
        assertTrue(r.norm.valid)
    }

    @Test fun assemblyDropsIndAndSmallText() {
        val lines = listOf(
            OcrLine("IND", Box(0f, 0f, 10f, 10f), 0.5f),
            OcrLine("MH 12 XY 9876", Box(15f, 0f, 200f, 40f), 0.93f),
            OcrLine("ABC MOTORS", Box(30f, 42f, 120f, 50f), 0.6f)
        )
        val r = PlateTextAssembler.assemble(lines, "orig")!!
        assertEquals("MH12XY9876", r.norm.normalized)
    }
}
