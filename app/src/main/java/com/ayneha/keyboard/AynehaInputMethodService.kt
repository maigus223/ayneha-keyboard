// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 MAIGUS

package com.ayneha.keyboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

class AynehaInputMethodService : InputMethodService(), KeyboardActionListener {

    private lateinit var keyboardView: AynehaKeyboardView
    private lateinit var toolbarView: AynehaToolbarView
    private var config: KeyboardConfig? = null

    companion object {
        private const val TOOLBAR_HEIGHT_DP = 40f
        private const val KEYBOARD_HEIGHT_DP = 280f
    }

    override fun onCreateInputView(): View {
        config = KeyboardConfigLoader.load(this)

        val prefs = getSharedPreferences(AynehaPrefs.PREFS_NAME, MODE_PRIVATE)
        val haptic = prefs.getBoolean(AynehaPrefs.KEY_HAPTIC, true)

        keyboardView = AynehaKeyboardView(this).apply {
            actionListener = this@AynehaInputMethodService
            hapticEnabled = haptic
        }

        toolbarView = AynehaToolbarView(this).apply {
            hapticEnabled = haptic
            onSettings = { openSettings() }
            onSwitchView = { targetView ->
                keyboardView.switchToView(targetView)
                onSwitchView(targetView)
            }
        }

        // The keyboard draws its own manual right-to-left key placement (see
        // AynehaKeyboardView.layoutCurrentRows); pin this container to LTR so the system
        // never applies its own RTL mirroring on top of ours (which would undo it) when the
        // host app or device locale is itself RTL.
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(ContextCompat.getColor(this@AynehaInputMethodService, R.color.keyboard_bg))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(TOOLBAR_HEIGHT_DP + KEYBOARD_HEIGHT_DP).toInt()
            )
            addView(toolbarView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TOOLBAR_HEIGHT_DP).toInt()))
            addView(keyboardView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(KEYBOARD_HEIGHT_DP).toInt()))
        }

        config?.let { keyboardView.setConfig(it, "alphabet") }
        return container
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Always resume on the alphabet page when a new field is focused.
        keyboardView.switchToView("alphabet")
    }

    // Android's default IME behaviour switches to a full-screen "extract view" for certain
    // fields/orientations (landscape, some search bars…), which is exactly the "le clavier
    // prend tout l'écran" bug: force it off so the keyboard always stays a fixed-height bar
    // pinned at the bottom, in every host app.
    override fun onEvaluateFullscreenMode(): Boolean = false

    // -----------------------------------------------------------------
    // KeyboardActionListener
    // -----------------------------------------------------------------

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onAction(action: String) {
        val ic = currentInputConnection ?: return
        when (action) {
            "backspace" -> {
                // Delete one character before the cursor. For AYNEHA glyphs this is the
                // RLO-glyph-PDF wrapper (see wrapRtl/AynehaKeyboardEditing) — deleting all
                // 3 or 4 code units together (4 for plane-16 AYNEHA glyphs) keeps backspace behaving like a single erase, the
                // same principle used for Arabic/N'Ko input and erasing.
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    ic.commitText("", 1)
                } else {
                    val before = ic.getTextBeforeCursor(AynehaKeyboardEditing.MAX_WRAPPER_UNITS, 0) ?: ""
                    ic.deleteSurroundingText(AynehaKeyboardEditing.backspaceLength(before), 0)
                }
            }
            "enter" -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    override fun onSwitchView(targetView: String) {
        // AynehaKeyboardView already swapped its own displayed layout; nothing else to do
        // unless we later want per-view haptics/analytics.
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
