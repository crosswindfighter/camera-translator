package com.example.cameratranslator

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * ViewPort kullanıldığında ImageAnalysis çıktısı PreviewView ile aynı görünür alanı
 * temsil eder; normalize koordinatlar doğrudan bitmap boyutuna ölçeklenir.
 */
object PreviewCoordinateMapper {

    fun mapNormalizedToBitmapRect(
        normalized: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Rect {
        val left = (normalized.left * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1)
        val top = (normalized.top * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1)
        val right = (normalized.right * bitmapWidth).toInt().coerceIn(left + 1, bitmapWidth)
        val bottom = (normalized.bottom * bitmapHeight).toInt().coerceIn(top + 1, bitmapHeight)
        return Rect(left, top, right, bottom)
    }
}

fun ImageProxy.toBitmapSafe(): Bitmap? {
    val rawBitmap = try {
        if (format == ImageFormat.YUV_420_888) {
            toNv21Bitmap()
        } else if (planes.isNotEmpty() && planes[0].pixelStride == 4) {
            toRgbaBitmapWithStride()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    } ?: return null

    // Görüntüyü cihaz oryantasyonuna göre döndür (WYSIWYG)
    // PreviewView.bitmap'in sağladığı davranışın aynısı.
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return rawBitmap

    val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
    rawBitmap.recycle()
    return rotated
}

private fun ImageProxy.toRgbaBitmapWithStride(): Bitmap? {
    val plane = planes[0]
    val buffer: ByteBuffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width

    buffer.rewind()
    val fullBitmap = Bitmap.createBitmap(
        width + rowPadding / pixelStride,
        height,
        Bitmap.Config.ARGB_8888
    )
    fullBitmap.copyPixelsFromBuffer(buffer)
    
    // ViewPort cropRect uygulayarak görüntüyü kısıtla
    val crop = this.cropRect
    val cropped = Bitmap.createBitmap(fullBitmap, crop.left, crop.top, crop.width(), crop.height())
    if (cropped != fullBitmap) fullBitmap.recycle()
    return cropped
}

private fun ImageProxy.toNv21Bitmap(): Bitmap? {
    val nv21 = try {
        yuv420888ToNv21(this)
    } catch (e: Exception) {
        return null
    }
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    // ViewPort cropRect kullanarak sadece görünür alanı JPEG'e sıkıştır
    yuvImage.compressToJpeg(this.cropRect, 95, out)
    val bytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val width = image.width
    val height = image.height
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    val yRowStride = yPlane.rowStride
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride

    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride

    val output = ByteArray(width * height * 3 / 2)

    // Copy Y plane with stride awareness
    for (row in 0 until height) {
        yBuffer.position(row * yRowStride)
        yBuffer.get(output, row * width, width)
    }

    // Copy UV plane (interleaved) with stride and pixel stride awareness
    var pos = width * height
    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            output[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride)
            output[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride)
        }
    }
    return output
}
