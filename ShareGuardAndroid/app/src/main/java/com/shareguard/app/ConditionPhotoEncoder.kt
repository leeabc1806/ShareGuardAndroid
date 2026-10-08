package com.shareguard.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Decode at bounded resolution and preserve the camera's portrait orientation. */
object ConditionPhotoEncoder {
    fun encode(resolver: ContentResolver, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "촬영된 사진을 읽을 수 없습니다." }
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 3840) options.inSampleSize *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("사진을 읽을 수 없습니다.")
        var current = decoded
        try {
            val ratio = minOf(1f, 1920f / maxOf(current.width, current.height))
            if (ratio < 1f) {
                val resized = Bitmap.createScaledBitmap(current, (current.width * ratio).roundToInt().coerceAtLeast(1), (current.height * ratio).roundToInt().coerceAtLeast(1), true)
                if (resized !== current) current.recycle()
                current = resized
            }
            val orientation = runCatching { resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } }.getOrNull()
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
                if (rotated !== current) current.recycle()
                current = rotated
            }
            return ByteArrayOutputStream().use { output ->
                check(current.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "사진 변환에 실패했습니다." }
                output.toByteArray().also { require(it.size <= 10 * 1024 * 1024) { "사진은 10MB 이하여야 합니다." } }
            }
        } finally {
            current.recycle()
        }
    }
}
