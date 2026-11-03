package com.puli.islanddrop

import android.content.Context
import android.os.Build
import android.provider.Settings

fun deviceName(c: Context): String {
    val fromSettings = runCatching {
        Settings.Global.getString(c.contentResolver, "device_name")
    }.getOrNull()
    if (!fromSettings.isNullOrBlank()) return fromSettings
    val model = Build.MODEL ?: "Android"
    return model
}
