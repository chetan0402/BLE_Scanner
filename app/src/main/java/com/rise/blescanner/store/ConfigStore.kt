package com.rise.blescanner.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class BleDeviceConfig(
    val name: String,
    val macAddress: String
)

class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "ble_scanner_config"
        private const val KEY_TRACKED_MACS = "tracked_macs"
    }

    fun saveTrackedMacs(macs: String) {
        prefs.edit { putString(KEY_TRACKED_MACS, macs) }
    }

    fun getTrackedMacs(): String {
        return prefs.getString(KEY_TRACKED_MACS, "") ?: ""
    }
}