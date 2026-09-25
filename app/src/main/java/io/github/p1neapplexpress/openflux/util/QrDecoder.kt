package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

object QrDecoder {

    private const val TAG = "QrDecoder"

    fun decodeFromUri(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeStream(inputStream, null, opts)
            inputStream.close()
            if (bitmap == null) return null
            try {
                decodeFromBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to decode QR from URI", e)
            null
        }
    }

    fun decodeFromBitmap(bitmap: Bitmap): String? {
        val target = if (bitmap.width > 1280 || bitmap.height > 1280) {
            val scale = 1280f / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }

        try {
            val width = target.width
            val height = target.height
            val pixels = IntArray(width * height)
            target.getPixels(pixels, 0, width, 0, 0, width, height)
    
            val source = RGBLuminanceSource(width, height, pixels)
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8"
            )
    
            try {
                val binary = BinaryBitmap(HybridBinarizer(source))
                val result = MultiFormatReader().decode(binary, hints)
                return result.text
            } catch (_: Exception) {}
    
            try {
                val binary = BinaryBitmap(GlobalHistogramBinarizer(source))
                val result = MultiFormatReader().decode(binary, hints)
                return result.text
            } catch (_: Exception) {}
    
            return null
        } finally {
            if (target !== bitmap) target.recycle()
        }
    }
}
