package com.vehiclescanner.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Produces OCR-friendly variants of one plate crop:
 *  - "color":  upscaled original
 *  - "clahe":  deskewed, contrast-equalised, lightly sharpened grayscale (handles shadow / glare / night)
 *  - "binary": adaptive threshold of the above, polarity fixed to dark text on white
 */
object PlateEnhancer {

    /** Target text height for ML Kit (it needs ≥ ~16-24 px per character). */
    private const val TARGET_HEIGHT = 140
    private const val MAX_WIDTH = 1400

    data class Variant(val name: String, val bitmap: Bitmap)

    /** Only the upscaled colour crop (cheap; used for quick verification). */
    fun upscaledColor(crop: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(crop, src)
        val up = upscale(src)
        src.release()
        return toBitmap(up).also { up.release() }
    }

    fun variants(crop: Bitmap): List<Variant> {
        val src = Mat()
        Utils.bitmapToMat(crop, src)
        val up = upscale(src)
        src.release()

        val out = ArrayList<Variant>(3)
        out += Variant("color", toBitmap(up))

        val gray = Mat()
        Imgproc.cvtColor(up, gray, Imgproc.COLOR_RGBA2GRAY)
        up.release()
        deskew(gray)

        val clahe: CLAHE = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
        val eq = Mat()
        clahe.apply(gray, eq)
        val blur = Mat()
        Imgproc.GaussianBlur(eq, blur, Size(0.0, 0.0), 2.0)
        val sharp = Mat()
        Core.addWeighted(eq, 1.6, blur, -0.6, 0.0, sharp)
        out += Variant("clahe", toBitmap(sharp))

        val bin = Mat()
        val block = (sharp.rows() / 2).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(15)
        Imgproc.adaptiveThreshold(sharp, bin, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 10.0)
        // Make sure text is dark on light: if most pixels are dark, invert.
        if (Core.mean(bin).`val`[0] < 110) Core.bitwise_not(bin, bin)
        out += Variant("binary", toBitmap(bin))

        gray.release(); eq.release(); blur.release(); sharp.release(); bin.release()
        return out
    }

    private fun upscale(src: Mat): Mat {
        val dst = Mat()
        var s = TARGET_HEIGHT.toDouble() / src.rows()
        if (src.cols() * s > MAX_WIDTH) s = MAX_WIDTH.toDouble() / src.cols()
        s = max(1.0, s)
        if (s > 1.01) Imgproc.resize(src, dst, Size(src.cols() * s, src.rows() * s), 0.0, 0.0, Imgproc.INTER_CUBIC)
        else src.copyTo(dst)
        return dst
    }

    /** Rotate the crop so the dominant text line is horizontal (only for 2°–30° tilts). */
    private fun deskew(gray: Mat) {
        val bin = Mat()
        Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(9.0, gray.cols() / 12.0), 3.0))
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, k)
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val big = contours.maxByOrNull { Imgproc.contourArea(it) }
        if (big != null && Imgproc.contourArea(big) > gray.total() * 0.05) {
            // Orientation of the text blob from its second-order central moments (image y points down,
            // so a positive angle means the line slopes down to the right).
            val mo = Imgproc.moments(big)
            val angle = Math.toDegrees(0.5 * kotlin.math.atan2(2 * mo.mu11, mo.mu20 - mo.mu02))
            if (abs(angle) in 2.0..30.0) {
                val m = Imgproc.getRotationMatrix2D(Point(gray.cols() / 2.0, gray.rows() / 2.0), angle, 1.0)
                Imgproc.warpAffine(gray, gray, m, gray.size(), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(0.0))
                m.release()
            }
        }
        contours.forEach { it.release() }
        bin.release(); k.release()
    }

    private fun toBitmap(m: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(m.cols(), m.rows(), Bitmap.Config.ARGB_8888)
        val tmp = if (m.type() == CvType.CV_8UC1) Mat().also { Imgproc.cvtColor(m, it, Imgproc.COLOR_GRAY2RGBA) } else m
        Utils.matToBitmap(tmp, bmp)
        if (tmp !== m) tmp.release()
        return bmp
    }

    @Suppress("unused")
    private fun clamp(v: Int, lo: Int, hi: Int) = min(hi, max(lo, v))
}
