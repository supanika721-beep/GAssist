package com.aquille.gassist

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var btnSaveKey: TextView
    private lateinit var btnBack: TextView
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsManager = SettingsManager(this)

        etApiKey   = findViewById(R.id.etApiKey)
        btnSaveKey = findViewById(R.id.btnSaveKey)
        btnBack    = findViewById(R.id.btnBack)

        // Pre-fill existing key
        val existing = settingsManager.getApiKey()
        if (existing.isNotEmpty()) {
            etApiKey.setText(existing)
        }

        btnBack.setOnClickListener { finish() }

        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "API Key cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settingsManager.saveApiKey(key)
            Toast.makeText(this, "Key saved ✓", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
