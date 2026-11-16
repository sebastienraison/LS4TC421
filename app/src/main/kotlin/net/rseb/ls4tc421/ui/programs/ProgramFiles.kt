package net.rseb.ls4tc421.ui.programs

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rseb.ls4tc421.model.ImportError
import net.rseb.ls4tc421.model.ImportResult
import net.rseb.ls4tc421.model.ProgramFile
import net.rseb.ls4tc421.model.ProgramSpec

/**
 * Export and import through the system file picker and the share sheet: no storage permission.
 * Shared files are written to the app's cache (folder `shared/`, served by the FileProvider).
 */
object ProgramFiles {
    const val MIME = "application/json"

    /** Types offered by the import picker: some file managers do not know `.json`. */
    val IMPORT_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")

    private fun authority(context: Context) = "${context.packageName}.files"

    /** Writes [spec] to [uri] (chosen with the « create document » picker). */
    suspend fun save(context: Context, uri: Uri, spec: ProgramSpec): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(ProgramFile.encode(spec).toByteArray(Charsets.UTF_8)) } != null
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** The share sheet for [spec], as a `.json` attachment. */
    suspend fun shareIntent(context: Context, spec: ProgramSpec): Intent? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // only the last shared file is kept
            val file = File(dir, ProgramFile.fileName(spec))
            file.writeText(ProgramFile.encode(spec), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, spec.name)
                clipData = ClipData.newRawUri(spec.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(send, null)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Reads and checks a program file; nothing is stored here. */
    suspend fun read(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = input.readNBytesCompat(ProgramFile.MAX_BYTES + 1)
                buffer
            } ?: return@withContext ImportResult.Refused(ImportError.NotJson("unreadable"))
            if (bytes.size > ProgramFile.MAX_BYTES) return@withContext ImportResult.Refused(ImportError.TooLarge)
            ProgramFile.decode(bytes.toString(Charsets.UTF_8))
        } catch (e: IOException) {
            ImportResult.Refused(ImportError.NotJson(e.message.orEmpty()))
        } catch (e: SecurityException) {
            ImportResult.Refused(ImportError.NotJson(e.message.orEmpty()))
        }
    }

    /** At most [limit] bytes (InputStream.readNBytes needs API 33). */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
