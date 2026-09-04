package app.facecard.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Shares the collage bitmap through the standard Android share sheet.
 * Stages a JPEG under cache (FileProvider — see res/xml/file_paths.xml),
 * so no storage permission is ever needed for sharing.
 */
object ShareHelper {

    suspend fun shareIntent(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
    ): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.applicationContext.cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "facecard_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        val uri = FileProvider.getUriForFile(
            context.applicationContext,
            "${context.applicationContext.packageName}.fileprovider",
            file,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "$displayName — made with FaceCard, on-device")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
