package com.puli.islanddrop

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

object Saver {
    fun create(c: Context, info: FileInfo): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, info.name)
            put(MediaStore.MediaColumns.MIME_TYPE, info.mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IslandDrop")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        return c.contentResolver.insert(collection, values)
            ?: throw java.io.IOException("Couldn't create ${info.name}")
    }

    fun finish(c: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            c.contentResolver.update(uri, values, null, null)
        }
    }
}
