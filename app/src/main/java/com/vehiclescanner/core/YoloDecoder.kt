package com.vehiclescanner.core

/**
 * Decodes raw TFLite YOLO output into boxes in model-input pixel space.
 *
 * Supported layouts (auto-detected from the output shape):
 *  - YOLOv8 / v11 (Ultralytics export):   [1, 4+nc, N]   rows = cx,cy,w,h,cls0..clsN
 *  - YOLOv5 (Ultralytics export):         [1, N, 5+nc]   cols = cx,cy,w,h,obj,cls0..
 *  - End-to-end / NMS-free (v10, YOLO26): [1, K, 6]      cols = x1,y1,x2,y2,score,cls  (K ≤ 1000)
 * Coordinates may be normalised (0..1) or in pixels; both are handled.
 */
object YoloDecoder {

    enum class Layout { V8, V5, END_TO_END }

    fun detectLayout(shape: IntArray): Layout {
        require(shape.size == 3) { "Expected a 3-D output tensor, got ${shape.contentToString()}" }
        val a = shape[1]; val b = shape[2]
        return when {
            b == 6 && a <= 1000 -> Layout.END_TO_END
            a < b -> Layout.V8
            else -> Layout.V5
        }
    }

    /**
     * @param out flat output (batch 1)
     * @param shape output tensor shape
     * @param inputSize model input side (square)
     * @param classIds which class indices count as "plate"; null = any class
     */
    fun decode(
        out: FloatArray,
        shape: IntArray,
        inputSize: Int,
        scoreThreshold: Float,
        classIds: Set<Int>? = null,
        layout: Layout = detectLayout(shape)
    ): List<Box> {
        val boxes = ArrayList<Box>()
        when (layout) {
            Layout.V8 -> {
                val c = shape[1]; val n = shape[2]
                val nc = c - 4
                if (nc < 1) return boxes
                val norm = looksNormalized(out, n, stride = 1, rowStride = n, count = 4)
                val k = if (norm) inputSize.toFloat() else 1f
                for (i in 0 until n) {
                    var best = 0f; var bestC = -1
                    for (cl in 0 until nc) {
                        if (classIds != null && cl !in classIds) continue
                        val s = out[(4 + cl) * n + i]
                        if (s > best) { best = s; bestC = cl }
                    }
                    if (bestC < 0 || best < scoreThreshold) continue
                    val cx = out[i] * k; val cy = out[n + i] * k
                    val w = out[2 * n + i] * k; val h = out[3 * n + i] * k
                    boxes += Box(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, best)
                }
            }
            Layout.V5 -> {
                val n = shape[1]; val c = shape[2]
                val nc = c - 5
                if (nc < 1) return boxes
                val norm = looksNormalized(out, n, stride = c, rowStride = 1, count = 4)
                val k = if (norm) inputSize.toFloat() else 1f
                for (i in 0 until n) {
                    val base = i * c
                    val obj = out[base + 4]
                    if (obj < scoreThreshold) continue
                    var best = 0f; var bestC = -1
                    for (cl in 0 until nc) {
                        if (classIds != null && cl !in classIds) continue
                        val s = out[base + 5 + cl]
                        if (s > best) { best = s; bestC = cl }
                    }
                    val score = obj * best
                    if (bestC < 0 || score < scoreThreshold) continue
                    val cx = out[base] * k; val cy = out[base + 1] * k
                    val w = out[base + 2] * k; val h = out[base + 3] * k
                    boxes += Box(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, score)
                }
            }
            Layout.END_TO_END -> {
                val n = shape[1]
                val norm = looksNormalized(out, n, stride = 6, rowStride = 1, count = 4)
                val k = if (norm) inputSize.toFloat() else 1f
                for (i in 0 until n) {
                    val base = i * 6
                    val score = out[base + 4]
                    val cl = out[base + 5].toInt()
                    if (score < scoreThreshold) continue
                    if (classIds != null && cl !in classIds) continue
                    boxes += Box(out[base] * k, out[base + 1] * k, out[base + 2] * k, out[base + 3] * k, score)
                }
            }
        }
        return boxes.filter { it.width > 1f && it.height > 1f }
    }

    /** Sample the coordinate values: if all are ≤ ~1.5 the model emits normalised coordinates. */
    private fun looksNormalized(out: FloatArray, n: Int, stride: Int, rowStride: Int, count: Int): Boolean {
        var max = 0f
        val step = maxOf(1, n / 200)
        var i = 0
        while (i < n) {
            for (j in 0 until count) {
                val idx = if (rowStride == 1) i * stride + j else j * rowStride + i
                if (idx < out.size) { val v = out[idx]; if (v > max) max = v }
            }
            i += step
        }
        return max <= 1.5f
    }
}
