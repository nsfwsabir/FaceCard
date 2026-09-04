package app.facecard.data.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Saves the collage bitmap to the gallery (Pictures/FaceCard).
 * Scoped-storage compliant: RELATIVE_PATH + IS_PENDING on API 29+;
 * legacy external volume on API 26–28 (needs WRITE_EXTERNAL_STORAGE,
 * requested at save time — see manifest maxSdkVersion 28).
 */
object MediaStoreSaver {

    suspend fun save(context: Context, bitmap: Bitmap, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver
            val fileName = "${sanitize(displayName)}.jpg"
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FaceCard")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values)
                ?: throw IOException("Couldn't create gallery entry")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                        throw IOException("JPEG encode failed")
                    }
                } ?: throw IOException("Couldn't open gallery output")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }.also { resolver.update(uri, it, null, null) }
                }
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            uri
        }

    private fun sanitize(name: String): String {
        val base = name.substringBeforeLast('.').ifBlank { "collage" }
        return buildString {
            append("facecard_")
            for (ch in base.take(40)) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_')
            }
            append('_')
            append(System.currentTimeMillis() / 1000)
        }
    }
}
