package com.puli.islanddrop

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException

/** Writes incoming files to Downloads/Island Drop (no storage permission needed on Android 10+). */
object Saver {
    fun create(c: Context, f: FileInfo): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, f.mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Island Drop")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't create file in Downloads")
    }

    fun finish(c: Context, uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        c.contentResolver.update(uri, v, null, null)
    }
}
