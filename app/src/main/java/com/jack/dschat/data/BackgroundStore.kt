package com.jack.dschat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import kotlin.math.max

/** 把 (width, height) 等比缩到长边不超过 limit；本来就不超过的原样返回 */
fun fitWithin(width: Int, height: Int, limit: Int): Pair<Int, Int> {
    val long = max(width, height)
    if (long <= limit) return width to height
    val scale = limit.toFloat() / long
    return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
}

/** 读一张图并缩到长边不超过 limit，照片的方向转正；读不出来抛异常或返回 null */
fun decodeImage(context: Context, uri: Uri, limit: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        // 新系统的解码器会自己按照片里记的方向转正
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (w, h) = fitWithin(info.size.width, info.size.height, limit)
            decoder.setTargetSize(w, h)
        }
    }
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2
    val raw = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    // 老系统要自己读方向：相机拍的竖图，文件里常常是横着存的
    val degrees = resolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    val (w, h) = fitWithin(raw.width, raw.height, limit)
    val matrix = Matrix().apply { postScale(w.toFloat() / raw.width, h.toFloat() / raw.height); postRotate(degrees) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}

/**
 * 聊天背景图。选中的图片缩小后复制到应用私有目录里：
 * 原图删了、挪了位置都不影响，也不用申请读相册的权限，占的内存也小。
 */
class BackgroundStore(private val context: Context) {
    private val file = File(context.filesDir, "background.jpg")

    fun load(): Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null

    /** 读不出来（不是图片、文件坏了）返回 null，原来的背景不动 */
    fun import(uri: Uri): Bitmap? {
        val bitmap = runCatching { decode(uri) }.getOrNull() ?: return null
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(file)
        return bitmap
    }

    fun clear() {
        file.delete()
    }

    private fun decode(uri: Uri): Bitmap? = decodeImage(context, uri, LIMIT)

    private companion object {
        /** 长边最多这么多像素：铺满手机屏幕够清楚了 */
        const val LIMIT = 2000
    }
}
