package com.puli.islanddrop

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

sealed class Outgoing {
    data class Files(val uris: List<Uri>, val infos: List<FileInfo>) : Outgoing()
    data class Text(val text: String) : Outgoing()

    companion object {
        fun describe(c: Context, uri: Uri): FileInfo {
            var name = "file"
            var size = -1L
            c.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
            val mime = c.contentResolver.getType(uri) ?: "application/octet-stream"
            return FileInfo(name, size, mime)
        }
    }
}
