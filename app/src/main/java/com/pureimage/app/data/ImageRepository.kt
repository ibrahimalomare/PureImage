package com.pureimage.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class ImageRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.cacheDir, "working_images").apply { mkdirs() }

    // All selected content is copied immediately: no persistent gallery grant needed.
    suspend fun importImage(uri: Uri): File = withContext(Dispatchers.IO) {
        val file = File.createTempFile("input_", ".img", directory)
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IOException("Unable to open the selected image.")
            input.use { source -> file.outputStream().use { copyLimited(source, it) } }
            validate(file)
            currentCoroutineContext().ensureActive()
            file
        } catch (e: Exception) { file.delete(); throw e }
    }

    suspend fun reconstruct(source: File): File = withContext(Dispatchers.Default) {
        validate(source)
        val output = File.createTempFile("output_", ".png", directory)
        var decoded: Bitmap? = null
        var result: Bitmap? = null
        try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(
                    maxOf(1, (info.size.width * scale).toInt()),
                    maxOf(1, (info.size.height * scale).toInt())
                )
            }
            decoded = bitmap
            currentCoroutineContext().ensureActive()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val coroutineContext = currentCoroutineContext()
            val cleaned = LocalCleanup.apply(pixels, bitmap.width, bitmap.height) {
                coroutineContext.ensureActive()
            }
            val cleanedBitmap = Bitmap.createBitmap(cleaned, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            result = cleanedBitmap
            output.outputStream().use {
                check(cleanedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Unable to encode image." }
            }
            require(output.length() <= MAX_BYTES) { "Processed image exceeds 25 MiB." }
            currentCoroutineContext().ensureActive()
            output
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            decoded?.recycle()
            result?.recycle()
        }
    }

    suspend fun saveToGallery(file: File): Uri = withContext(Dispatchers.IO) {
        val mime = validate(file)
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PureImage_${UUID.randomUUID()}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PureImage")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create a gallery entry.")
        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("Unable to write to the gallery.")
            stream.use { output -> file.inputStream().use { copyLimited(it, output) } }
            currentCoroutineContext().ensureActive()
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            check(published == 1) { "Unable to publish the gallery image." }
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    suspend fun delete(file: File?) = withContext(Dispatchers.IO) { file?.delete(); Unit }

    private fun validate(file: File): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Invalid or unsupported image." }
        require(options.outWidth.toLong() * options.outHeight <= 40_000_000L) {
            "Please select an image no larger than 40 megapixels."
        }
        val mime = options.outMimeType
        require(mime in setOf("image/png", "image/jpeg", "image/webp")) {
            "Please use a PNG, JPEG, or WebP image."
        }
        return requireNotNull(mime)
    }

    private suspend fun copyLimited(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            require(total <= MAX_BYTES) { "Image exceeds the 25 MiB limit." }
            output.write(buffer, 0, count)
        }
        require(total > 0) { "Image is empty." }
    }

    companion object { private const val MAX_BYTES = 25L * 1024 * 1024 }
}
