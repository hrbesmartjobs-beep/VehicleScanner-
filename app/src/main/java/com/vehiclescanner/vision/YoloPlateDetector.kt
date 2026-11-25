package com.vehiclescanner.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.vehiclescanner.core.Box
import com.vehiclescanner.core.Letterbox
import com.vehiclescanner.core.YoloDecoder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * On-device YOLO licence-plate detector (TensorFlow Lite).
 * Works with Ultralytics YOLOv5/v8/v11/YOLO26 TFLite exports (float32, float16 or int8),
 * NHWC or NCHW input, single "plate" class or a multi-class model (see [plateClassIds]).
 */
class YoloPlateDetector(
    model: MappedByteBuffer,
    private val plateClassIds: Set<Int>? = null,
    threads: Int = 4
) : PlateDetector, Closeable {

    override val name = "YOLO (TFLite)"

    private val interpreter = Interpreter(model, Interpreter.Options().apply {
        setNumThreads(threads)
        setUseXNNPACK(true)
    })

    private val inTensor = interpreter.getInputTensor(0)
    private val outTensor = interpreter.getOutputTensor(0)
    private val inShape = inTensor.shape()                  // [1,H,W,3] or [1,3,H,W]
    private val nchw = inShape.size == 4 && inShape[1] == 3 && inShape[3] != 3
    val inputSize: Int = if (nchw) inShape[2] else inShape[1]
    private val inType = inTensor.dataType()
    private val outShape = outTensor.shape()
    private val outType = outTensor.dataType()
    private val outCount = outShape.fold(1) { a, b -> a * b }

    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(inTensor.numBytes()).order(ByteOrder.nativeOrder())
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(outTensor.numBytes()).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(inputSize * inputSize)
    private val canvasBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        require(outShape.size == 3) { "Unsupported YOLO output shape ${outShape.contentToString()}" }
        YoloDecoder.detectLayout(outShape)   // throws early for unsupported models
    }

    val description: String
        get() = "input ${inShape.contentToString()} ${inType.name}, output ${outShape.contentToString()} (${YoloDecoder.detectLayout(outShape)})"

    @Synchronized
    override fun detect(image: Bitmap, threshold: Float): List<Box> {
        val lb = Letterbox.of(image.width, image.height, inputSize)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(image, null, RectF(lb.padX, lb.padY, lb.padX + image.width * lb.scale, lb.padY + image.height * lb.scale), paint)
        canvasBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        fillInput()

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        val out = readOutput()

        return YoloDecoder.decode(out, outShape, inputSize, threshold, plateClassIds)
            .map { lb.unmap(it).clip(image.width, image.height) }
            .filter { it.width >= 6 && it.height >= 4 }
    }

    private fun fillInput() {
        inputBuffer.rewind()
        val q = inTensor.quantizationParams()
        val plane = inputSize * inputSize
        fun put(v: Float) {
            when (inType) {
                DataType.FLOAT32 -> inputBuffer.putFloat(v)
                DataType.UINT8 -> inputBuffer.put(((v / q.scale) + q.zeroPoint).toInt().coerceIn(0, 255).toByte())
                DataType.INT8 -> inputBuffer.put(((v / q.scale) + q.zeroPoint).toInt().coerceIn(-128, 127).toByte())
                else -> throw IllegalStateException("Unsupported input type $inType")
            }
        }
        if (!nchw) {
            for (p in pixels) {
                put(((p shr 16) and 0xFF) / 255f); put(((p shr 8) and 0xFF) / 255f); put((p and 0xFF) / 255f)
            }
        } else {
            for (c in 0 until 3) {
                val shift = 16 - c * 8
                for (i in 0 until plane) put(((pixels[i] shr shift) and 0xFF) / 255f)
            }
        }
        inputBuffer.rewind()
    }

    private fun readOutput(): FloatArray {
        outputBuffer.rewind()
        val out = FloatArray(outCount)
        when (outType) {
            DataType.FLOAT32 -> outputBuffer.asFloatBuffer().get(out)
            DataType.UINT8, DataType.INT8 -> {
                val q = outTensor.quantizationParams()
                for (i in 0 until outCount) {
                    val raw = if (outType == DataType.UINT8) outputBuffer.get().toInt() and 0xFF else outputBuffer.get().toInt()
                    out[i] = (raw - q.zeroPoint) * q.scale
                }
            }
            else -> throw IllegalStateException("Unsupported output type $outType")
        }
        return out
    }

    override fun close() { interpreter.close(); canvasBitmap.recycle() }
}

interface PlateDetector {
    val name: String
    /** Returns plate boxes in [image] pixel coordinates. */
    fun detect(image: Bitmap, threshold: Float): List<Box>
}
