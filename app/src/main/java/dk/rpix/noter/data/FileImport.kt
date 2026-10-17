package dk.rpix.noter.data

import android.net.Uri
import android.provider.OpenableColumns
import dk.rpix.noter.NoterApp
import dk.rpix.noter.core.Note
import dk.rpix.noter.core.NoteFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Åbn filer og delt tekst som nye noter, og gem noter som filer (afsnit 7 i docs/SPEC.md). */
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

    /** Skriv [text] til en fil, brugeren har valgt. false, hvis det ikke lykkedes. */
    suspend fun write(app: NoterApp, uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
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
