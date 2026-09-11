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
        private const val KEY_RECORD_ACCEL = "record_accel"
        private const val KEY_RECORD_GYRO = "record_gyro"
        private const val KEY_SHOW_VISUALIZER = "show_visualizer"
    }

    fun saveTrackedMacs(macs: String) {
        prefs.edit { putString(KEY_TRACKED_MACS, macs) }
    }

    fun getTrackedMacs(): String {
        return prefs.getString(KEY_TRACKED_MACS, "") ?: ""
    }

    fun saveRecordAccel(enable: Boolean) {
        prefs.edit { putBoolean(KEY_RECORD_ACCEL, enable) }
    }

    fun getRecordAccel(): Boolean {
        return prefs.getBoolean(KEY_RECORD_ACCEL, true)
    }

    fun saveRecordGyro(enable: Boolean) {
        prefs.edit { putBoolean(KEY_RECORD_GYRO, enable) }
    }

    fun getRecordGyro(): Boolean {
        return prefs.getBoolean(KEY_RECORD_GYRO, true)
    }

    fun saveShowVisualizer(enable: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_VISUALIZER, enable) }
    }

    fun getShowVisualizer(): Boolean {
        return prefs.getBoolean(KEY_SHOW_VISUALIZER, false)
    }
}