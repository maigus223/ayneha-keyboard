// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 MAIGUS

package com.ayneha.keyboard

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat

/**
 * Standalone in-app tester. Mirrors the web preview (composition zone + AYNEHA IME) so the
 * writing system can be exercised without switching the device's system-wide keyboard.
 * The composition zone is enlarged to fill all remaining vertical space above the keyboard.
 */
class TestActivity : AppCompatActivity(), KeyboardActionListener {

    private lateinit var compositionZone: EditText
    private lateinit var keyboardView: AynehaKeyboardView
    private lateinit var toolbarView: AynehaToolbarView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_test)

        compositionZone = findViewById(R.id.compositionZone)
        ResourcesCompat.getFont(this, R.font.ayneha_pro)?.let { compositionZone.typeface = it }
        compositionZone.showSoftInputOnFocus = false
        setupGlyphAccurateCursorPlacement()

        findViewById<ImageButton>(R.id.btnShare).setOnClickListener {
            AynehaShareImage.share(this, compositionZone)
        }

        val prefs = getSharedPreferences(AynehaPrefs.PREFS_NAME, MODE_PRIVATE)
        val haptic = prefs.getBoolean(AynehaPrefs.KEY_HAPTIC, true)

        keyboardView = AynehaKeyboardView(this).apply {
            actionListener = this@TestActivity
            hapticEnabled = haptic
        }
        val keyboardContainer = findViewById<FrameLayout>(R.id.keyboardContainer)
        keyboardContainer.addView(
            keyboardView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        toolbarView = AynehaToolbarView(this).apply {
            hapticEnabled = haptic
            onSettings = { startActivity(Intent(this@TestActivity, SettingsActivity::class.java)) }
            onSwitchView = { targetView -> keyboardView.switchToView(targetView) }
        }
        val toolbarContainer = findViewById<FrameLayout>(R.id.toolbarContainer)
        toolbarContainer.addView(
            toolbarView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val config = KeyboardConfigLoader.load(this)
        keyboardView.setConfig(config, "alphabet")
    }

    // -----------------------------------------------------------------
    // KeyboardActionListener — writes directly into the composition EditText
    // -----------------------------------------------------------------

    override fun onText(text: String) {
        val start = compositionZone.selectionStart.coerceAtLeast(0)
        val end = compositionZone.selectionEnd.coerceAtLeast(0)
        compositionZone.text.replace(minOf(start, end), maxOf(start, end), text)
        forceFullBidiRelayout(minOf(start, end) + text.length)
    }

    override fun onAction(action: String) {
        when (action) {
            "backspace" -> {
                val start = compositionZone.selectionStart
                val end = compositionZone.selectionEnd
                if (start != end) {
                    compositionZone.text.delete(minOf(start, end), maxOf(start, end))
                    forceFullBidiRelayout(minOf(start, end))
                } else if (start > 0) {
                    val before = compositionZone.text.subSequence(maxOf(0, start - AynehaKeyboardEditing.MAX_WRAPPER_UNITS), start)
                    val len = AynehaKeyboardEditing.backspaceLength(before)
                    compositionZone.text.delete(start - len, start)
                    forceFullBidiRelayout(start - len)
                }
            }
            "enter" -> {
                val start = compositionZone.selectionStart.coerceAtLeast(0)
                compositionZone.text.insert(start, "\n")
                forceFullBidiRelayout(start + 1)
            }
        }
    }

    // -----------------------------------------------------------------
    // Forces a full, from-scratch bidi/layout recomputation instead of the
    // incremental update EditText's internal DynamicLayout normally does after
    // an edit. With this many per-glyph RLO...PDF override runs packed together,
    // the incremental path can settle on the wrong visual order (glyphs get
    // silently reshuffled on screen even though the underlying string is
    // correct) until something forces a full relayout. Re-assigning the text to
    // a fresh Editable instance forces that full rebuild every time.
    // -----------------------------------------------------------------
    private fun forceFullBidiRelayout(cursorPos: Int) {
        val fresh: Editable = SpannableStringBuilder(compositionZone.text)
        compositionZone.setText(fresh, TextView.BufferType.EDITABLE)
        compositionZone.setSelection(cursorPos.coerceIn(0, compositionZone.text.length))
    }

    override fun onSwitchView(targetView: String) {
        // layout swap already handled inside AynehaKeyboardView
    }

    // -----------------------------------------------------------------
    // Glyph-accurate tap-to-cursor placement.
    //
    // Every typed AYNEHA glyph is stored as an RLO-glyph-PDF unit (4 UTF-16 units for plane 16) (see
    // wrapRtl/AynehaKeyboardEditing) so it displays correctly RTL wherever it ends up.
    // The side effect: a run of text can contain many zero-width RLO/PDF formatting
    // characters packed between glyphs, and Android's own bidi hit-testing
    // (TextView.getOffsetForPosition) is not reliable at telling them apart — a tap
    // meant for one glyph can resolve to a logical offset that lands *inside* a
    // triplet instead of at its boundary, which then breaks
    // AynehaKeyboardEditing.backspaceLength()'s exact RLO...PDF pattern check.
    //
    // Fix: bypass Android's default hit-testing for single taps and compute the
    // tapped glyph ourselves from the layout's own per-character horizontal
    // positions, then always snap the cursor to the logical boundary right AFTER
    // that glyph — which is visually to its LEFT in this RTL script — so the
    // existing backspace action can remove exactly the tapped glyph.
    //
    // NOTE: this only fixes the in-app test zone. A real host app's own EditText
    // (WhatsApp, etc.) handles its own taps; the IME has no way to override that.
    // -----------------------------------------------------------------
    private fun setupGlyphAccurateCursorPlacement() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val layout = compositionZone.layout ?: return false
                val text = compositionZone.text
                if (text.isEmpty()) return false

                // Touch coordinates -> text-relative coordinates (strip padding/scroll).
                val x = e.x - compositionZone.totalPaddingLeft + compositionZone.scrollX
                val y = (e.y - compositionZone.totalPaddingTop + compositionZone.scrollY).toInt()
                val line = layout.getLineForVertical(y).coerceIn(0, layout.lineCount - 1)
                val lineStart = layout.getLineStart(line)
                val lineEnd = layout.getLineEnd(line)

                var i = lineStart
                while (i < lineEnd) {
                    // A glyph is an RLO/PDF-wrapped unit: 4 UTF-16 units for plane-16 AYNEHA
                    // glyphs (RLO + surrogate pair + PDF), 3 for legacy BMP ones; anything
                    // else (space, newline, a raw symbol) is 1 unit, or 2 for a surrogate pair.
                    val unitLen = AynehaKeyboardEditing.unitLengthAt(text, i)
                    val boundaryAfter = (i + unitLen).coerceAtMost(text.length)
                    val edgeAfter = layout.getPrimaryHorizontal(boundaryAfter)   // visually left (RTL)
                    val edgeBefore = layout.getPrimaryHorizontal(i)             // visually right (RTL)
                    val lo = minOf(edgeAfter, edgeBefore)
                    val hi = maxOf(edgeAfter, edgeBefore)
                    if (x in lo..hi) {
                        forceFullBidiRelayout(boundaryAfter)
                        return true
                    }
                    i += unitLen
                }
                // Tap fell outside every unit's box on this line — let Android place it.
                return false
            }
        })

        compositionZone.setOnTouchListener { v, event ->
            v.requestFocus()
            if (gestureDetector.onTouchEvent(event)) {
                true
            } else {
                v.onTouchEvent(event)
            }
        }
    }
}
