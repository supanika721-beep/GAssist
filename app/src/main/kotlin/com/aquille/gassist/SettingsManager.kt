package com.aquille.gassist

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("gassist_settings", Context.MODE_PRIVATE)

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString("api_key", apiKey).apply()
    }

    fun getApiKey(): String {
        return prefs.getString("api_key", "") ?: ""
    }

    fun hasApiKey(): Boolean = getApiKey().isNotEmpty()
}
