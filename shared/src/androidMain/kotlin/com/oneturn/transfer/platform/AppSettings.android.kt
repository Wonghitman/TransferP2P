package com.oneturn.transfer.platform

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

private lateinit var settingsContext: Context

fun initSettingsContext(context: Context) {
    settingsContext = context.applicationContext
}

actual fun createAppSettings(): Settings =
    SharedPreferencesSettings(settingsContext.getSharedPreferences("transfer_p2p", Context.MODE_PRIVATE))
