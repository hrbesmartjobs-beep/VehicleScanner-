package com.vehiclescanner.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.vehiclescanner.core.Box
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {

    /** Longest side kept for processing. Big enough for far plates, small enough for ~50 MB of RAM. */
    const val MAX_SIDE = 4000

    class ImageLoadException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    /**
     * Decode a (possibly huge) image with subsampling and apply EXIF rotation.
     * Never decodes the full-resolution bitmap if it would exceed [maxSide].
     */
    fun load(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Note: with inJustDecodeBounds decodeStream() always returns null, so the stream itself is null-checked.
        val boundsStream = cr.openInputStream(uri) ?: throw ImageLoadException("Cannot open the selected image")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageLoadException("Unsupported or corrupt image")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = try {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (oom: OutOfMemoryError) {
            opts.inSampleSize = sample * 2
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } ?: throw ImageLoadException("Could not decode the image")

        // Final exact downscale if still above the limit.
        val longest = max(bmp.width, bmp.height)
        if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }

        val orientation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }

    fun crop(src: Bitmap, b: Box): Bitmap {
        val l = b.left.toInt().coerceIn(0, src.width - 1)
        val t = b.top.toInt().coerceIn(0, src.height - 1)
        val r = b.right.roundToInt().coerceIn(l + 1, src.width)
        val btm = b.bottom.roundToInt().coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, btm - t)
    }

    fun save(bmp: Bitmap, file: File, quality: Int = 92) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}
