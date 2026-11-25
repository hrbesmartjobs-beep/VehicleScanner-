package com.vehiclescanner.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Axis-aligned box in source-image pixel coordinates. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float = 1f) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = max(0f, width) * max(0f, height)
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f

    fun iou(o: Box): Float {
        val inter = intersection(o)
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun intersection(o: Box): Float {
        val w = min(right, o.right) - max(left, o.left)
        val h = min(bottom, o.bottom) - max(top, o.top)
        return if (w <= 0 || h <= 0) 0f else w * h
    }

    /** Fraction of the smaller box covered by the other (catches a plate box nested in a larger one). */
    fun overlapOfSmaller(o: Box): Float {
        val m = min(area, o.area)
        return if (m <= 0f) 0f else intersection(o) / m
    }

    fun offset(dx: Float, dy: Float) = copy(left = left + dx, right = right + dx, top = top + dy, bottom = bottom + dy)

    fun scale(s: Float) = copy(left = left * s, right = right * s, top = top * s, bottom = bottom * s)

    /** Grow by a fraction of width/height on each side and clip to the image. */
    fun expand(fx: Float, fy: Float, imgW: Int, imgH: Int): Box {
        val dx = width * fx; val dy = height * fy
        return copy(
            left = max(0f, left - dx), top = max(0f, top - dy),
            right = min(imgW.toFloat(), right + dx), bottom = min(imgH.toFloat(), bottom + dy)
        )
    }

    fun clip(imgW: Int, imgH: Int) = copy(
        left = left.coerceIn(0f, imgW.toFloat()), right = right.coerceIn(0f, imgW.toFloat()),
        top = top.coerceIn(0f, imgH.toFloat()), bottom = bottom.coerceIn(0f, imgH.toFloat())
    )
}

object Nms {
    /**
     * Greedy non-maximum suppression. Also suppresses boxes mostly contained
     * inside a higher-scoring box (duplicate detections from overlapping tiles).
     */
    fun apply(boxes: List<Box>, iouThreshold: Float = 0.45f, containThreshold: Float = 0.8f): List<Box> {
        val sorted = boxes.sortedByDescending { it.score }
        val keep = ArrayList<Box>()
        for (b in sorted) {
            if (keep.none { it.iou(b) > iouThreshold || it.overlapOfSmaller(b) > containThreshold }) keep += b
        }
        return keep
    }
}

data class Tile(val x: Int, val y: Int, val w: Int, val h: Int)

object Tiler {
    /**
     * Split a large image into overlapping square-ish tiles so that small, far
     * away plates in a crowded parking photo are still big enough for a
     * 640-px detector. Always includes the full image as the first "tile".
     */
    fun tiles(imgW: Int, imgH: Int, tileSize: Int = 1280, overlap: Float = 0.25f): List<Tile> {
        val out = ArrayList<Tile>()
        out += Tile(0, 0, imgW, imgH)
        if (max(imgW, imgH) <= (tileSize * 1.25f)) return out
        val step = (tileSize * (1f - overlap)).roundToInt().coerceAtLeast(1)
        val xs = starts(imgW, tileSize, step)
        val ys = starts(imgH, tileSize, step)
        for (y in ys) for (x in xs) out += Tile(x, y, min(tileSize, imgW - x), min(tileSize, imgH - y))
        return out
    }

    private fun starts(total: Int, size: Int, step: Int): List<Int> {
        if (total <= size) return listOf(0)
        val s = ArrayList<Int>()
        var p = 0
        while (p + size < total) { s += p; p += step }
        s += total - size
        return s.distinct()
    }
}

/** Letterbox transform for a square model input. */
data class Letterbox(val scale: Float, val padX: Float, val padY: Float, val inputSize: Int) {
    /** Map a box from model-input pixels back to the source (tile) pixels. */
    fun unmap(b: Box) = Box(
        (b.left - padX) / scale, (b.top - padY) / scale,
        (b.right - padX) / scale, (b.bottom - padY) / scale, b.score
    )

    companion object {
        fun of(srcW: Int, srcH: Int, inputSize: Int): Letterbox {
            val s = min(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)
            val nw = srcW * s; val nh = srcH * s
            return Letterbox(s, (inputSize - nw) / 2f, (inputSize - nh) / 2f, inputSize)
        }
    }
}
