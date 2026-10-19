// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 MAIGUS

package com.ayneha.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

object AynehaPrefs {
    const val PREFS_NAME = "ayneha_prefs"
    const val KEY_HAPTIC = "haptic_enabled"
    const val KEY_TIMESTAMP_ENABLED = "timestamp_enabled"
    const val KEY_PSEUDO = "user_pseudo"
    const val PSEUDO_MAX_LENGTH = 20

    /** Timestamp is on by default; the pseudo is empty (and so hidden) until the user sets one. */
    fun isTimestampEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_TIMESTAMP_ENABLED, true)

    fun getPseudo(context: android.content.Context): String =
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_PSEUDO, "") ?: ""
}

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(AynehaPrefs.PREFS_NAME, MODE_PRIVATE)
        val switchHaptic = findViewById<Switch>(R.id.switchHaptic)
        switchHaptic.isChecked = prefs.getBoolean(AynehaPrefs.KEY_HAPTIC, true)
        switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(AynehaPrefs.KEY_HAPTIC, isChecked).apply()
        }

        findViewById<android.widget.Button>(R.id.btnSystemSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        val checkTimestamp = findViewById<android.widget.CheckBox>(R.id.checkTimestamp)
        checkTimestamp.isChecked = AynehaPrefs.isTimestampEnabled(this)
        checkTimestamp.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(AynehaPrefs.KEY_TIMESTAMP_ENABLED, isChecked).apply()
        }

        val editPseudo = findViewById<android.widget.EditText>(R.id.editPseudo)
        editPseudo.filters = arrayOf(android.text.InputFilter.LengthFilter(AynehaPrefs.PSEUDO_MAX_LENGTH))
        editPseudo.setText(AynehaPrefs.getPseudo(this))
        editPseudo.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString(AynehaPrefs.KEY_PSEUDO, s?.toString().orEmpty()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }
}
