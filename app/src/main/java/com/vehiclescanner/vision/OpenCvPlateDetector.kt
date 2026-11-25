package com.vehiclescanner.vision

import android.graphics.Bitmap
import com.vehiclescanner.core.Box
import com.vehiclescanner.core.Nms
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Classical computer-vision licence-plate localiser (no ML model needed).
 *
 * Plates are high-contrast rectangles full of vertical character strokes. For
 * both polarities (dark text on white/yellow plates, light text on black/green
 * plates) we:
 *   1. black-hat / top-hat to isolate character strokes,
 *   2. horizontal Sobel gradient → blur → close with a wide kernel so a row of
 *      characters fuses into one blob,
 *   3. Otsu threshold, clean up, and keep contours with plate-like geometry.
 * Run at two scales so both near and far plates are found. Candidates are
 * later verified by OCR, so this stage favours recall over precision.
 */
class OpenCvPlateDetector : PlateDetector {

    override val name = "OpenCV (classical)"

    override fun detect(image: Bitmap, threshold: Float): List<Box> {
        val rgba = Mat()
        Utils.bitmapToMat(image, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()

        val found = ArrayList<Box>()
        for (workWidth in intArrayOf(1600, 2600)) {
            if (workWidth > gray.cols() * 1.2 && workWidth != 1600) continue
            val s = min(1.0, workWidth.toDouble() / gray.cols())
            val work = Mat()
            if (s < 1.0) Imgproc.resize(gray, work, Size(gray.cols() * s, gray.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
            else gray.copyTo(work)
            for (op in intArrayOf(Imgproc.MORPH_BLACKHAT, Imgproc.MORPH_TOPHAT)) {
                found += candidates(work, op).map { it.scale((1.0 / s).toFloat()) }
            }
            work.release()
        }
        gray.release()
        return Nms.apply(found, 0.3f, 0.7f).take(MAX_CANDIDATES)
    }

    private fun candidates(gray: Mat, op: Int): List<Box> {
        val w = gray.cols(); val h = gray.rows()
        val kw = max(13, w / 70).let { if (it % 2 == 0) it + 1 else it }
        val kh = max(5, kw / 3).let { if (it % 2 == 0) it + 1 else it }
        val rectKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kw.toDouble(), kh.toDouble()))

        val hat = Mat()
        Imgproc.morphologyEx(gray, hat, op, rectKernel)

        val gradX = Mat()
        Imgproc.Sobel(hat, gradX, CvType.CV_32F, 1, 0, -1)
        Core.absdiff(gradX, org.opencv.core.Scalar(0.0), gradX)
        Core.normalize(gradX, gradX, 0.0, 255.0, Core.NORM_MINMAX)
        gradX.convertTo(gradX, CvType.CV_8U)

        Imgproc.GaussianBlur(gradX, gradX, Size(5.0, 5.0), 0.0)
        Imgproc.morphologyEx(gradX, gradX, Imgproc.MORPH_CLOSE, rectKernel)
        val bin = Mat()
        Imgproc.threshold(gradX, bin, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

        val small = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.erode(bin, bin, small, Point(-1.0, -1.0), 2)
        Imgproc.dilate(bin, bin, small, Point(-1.0, -1.0), 2)

        // Keep only regions that also have bright/dark mass (the plate body), reduces texture noise.
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        val imgArea = (w * h).toDouble()
        val out = ArrayList<Box>()
        for (c in contours) {
            val r = Imgproc.boundingRect(c)
            if (r.width < 30 || r.height < 10) { c.release(); continue }
            val ar = r.width.toDouble() / r.height
            val areaFrac = r.width * r.height / imgArea
            val singleLine = ar in 2.0..7.5
            val twoLine = ar in 1.1..2.0 && r.height >= 24
            if ((!singleLine && !twoLine) || areaFrac < 0.00015 || areaFrac > 0.12) { c.release(); continue }

            // How rectangular is the blob? Rotated rectangles (angled plates) are allowed.
            val c2f = MatOfPoint2f(*c.toArray())
            val rr = Imgproc.minAreaRect(c2f)
            c2f.release()
            val rrArea = rr.size.width * rr.size.height
            val fill = if (rrArea > 0) Imgproc.contourArea(c) / rrArea else 0.0
            // Tilt of the long side from horizontal, independent of OpenCV's angle convention.
            var angle = if (rr.size.width >= rr.size.height) rr.angle else rr.angle + 90.0
            while (angle > 90.0) angle -= 180.0
            while (angle <= -90.0) angle += 180.0
            if (fill < 0.45 || abs(angle) > 35) { c.release(); continue }

            // Edge density inside the box: plates have many strokes.
            val roi = gradX.submat(r)
            val density = Core.mean(roi).`val`[0] / 255.0
            roi.release()

            val shapeScore = if (singleLine) 1.0 - min(1.0, abs(ar - 4.5) / 4.0) else 0.6
            val score = (0.45 * fill + 0.35 * shapeScore + 0.2 * min(1.0, density * 3)).toFloat()
            out += Box(r.x.toFloat(), r.y.toFloat(), (r.x + r.width).toFloat(), (r.y + r.height).toFloat(), score)
            c.release()
        }
        hat.release(); gradX.release(); bin.release(); rectKernel.release(); small.release()
        return out
    }

    companion object {
        /** Upper bound on candidates passed to OCR verification. */
        const val MAX_CANDIDATES = 80
    }
}
