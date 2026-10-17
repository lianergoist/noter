package dk.rpix.noter.data

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dk.rpix.noter.MainActivity
import dk.rpix.noter.NoterApp
import dk.rpix.noter.core.Note
import dk.rpix.noter.core.NoteFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Åbn filer og delt tekst som nye noter, og del noter som filer (afsnit 7 i docs/SPEC.md). */
object FileImport {
    /** Læs en fil og opret en note. null: kan ikke læses, er for stor, tom eller ikke tekst. */
    suspend fun fromUri(app: NoterApp, uri: Uri): Note? = withContext(Dispatchers.IO) {
        try {
            val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: uri.lastPathSegment
            val bytes = app.contentResolver.openInputStream(uri)?.use { readLimited(it) } ?: return@withContext null
            val text = NoteFile.decode(bytes) ?: return@withContext null
            create(app, NoteFile.parse(name, text))
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Tekst delt fra en anden app (ACTION_SEND). Emnet bliver titlen, hvis der er et. */
    suspend fun fromText(app: NoterApp, subject: String?, text: String): Note? = withContext(Dispatchers.IO) {
        val parsed = NoteFile.parse(null, text)
        create(app, if (subject.isNullOrBlank()) parsed else NoteFile.Content(subject.trim(), parsed.body))
    }

    /**
     * Skriv noten som Markdown-fil i cachen og byg en Del-intent til den (via FileProvider).
     * Tidligere delte filer slettes først. null, hvis filen ikke kunne skrives.
     */
    suspend fun share(app: NoterApp, title: String, body: String, fallbackName: String): Intent? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(app.cacheDir, "shared")
                dir.deleteRecursively()
                dir.mkdirs()
                val file = File(dir, NoteFile.fileName(title, "md", fallbackName))
                file.writeText(NoteFile.toMarkdown(title, body), Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title.trim())
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // DavNotes selv tilbydes ikke (det ville bare lave en kopi af noten)
                Intent.createChooser(send, null).putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(app, MainActivity::class.java)),
                )
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

    private fun create(app: NoterApp, c: NoteFile.Content): Note? {
        if (c.title.isBlank() && c.body.isBlank()) return null // en tom note oprettes ikke
        val note = app.store.add(c.title, c.body)
        app.requestSync()
        return note
    }

    /** Højst [NoteFile.MAX_BYTES]; null, hvis filen er større. */
    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > NoteFile.MAX_BYTES) return null
        }
    }
}
