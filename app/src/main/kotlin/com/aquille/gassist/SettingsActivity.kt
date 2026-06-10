package com.aquille.gassist

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var btnSave: Button
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsManager = SettingsManager(this)
        etApiKey = findViewById(R.id.etApiKey)
        btnSave = findViewById(R.id.btnSaveKey)

        // Tampilkan key yang sudah tersimpan (masked)
        val existing = settingsManager.getApiKey()
        if (existing.isNotEmpty()) {
            etApiKey.setText(existing)
        }

        btnSave.setOnClickListener {
            val inputKey = etApiKey.text.toString().trim()
            if (inputKey.isNotEmpty()) {
                settingsManager.saveApiKey(inputKey)
                Toast.makeText(this, "API Key saved!", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "API Key cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
