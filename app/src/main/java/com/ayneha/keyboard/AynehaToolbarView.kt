// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 MAIGUS

package com.ayneha.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Persistent toolbar pinned above the key grid on every page (LETTRES/SYMBOLES/NUMERIQUE/EMOJI
 * in the mockup): réglages (settings), bascule symboles, bascule pavé numérique, bascule lettres.
 * Drawn on canvas — like the rest of the keyboard's control icons — rather than with XML vector
 * drawables or raw glyph codepoints, so every device renders the exact same shape (see the
 * matching note on AynehaKeyboardView's icon methods).
 */
class AynehaToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onSettings: (() -> Unit)? = null
    var onSwitchView: ((String) -> Unit)? = null
    var hapticEnabled: Boolean = true

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    private val typeface: Typeface? = ResourcesCompat.getFont(context, R.font.ayneha_pro)
    private val iconColor = ContextCompat.getColor(context, R.color.muted_foreground)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(1.6f)
        color = iconColor
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@AynehaToolbarView.typeface
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
        color = iconColor
    }

    // Cell 0 = settings, 1 = symbols switch, 2 = numpad switch, 3 = alphabet switch — left to
    // right, matching the mockup exactly (this bar is NOT mirrored like the key grid below it).
    private val cellRects = ArrayList<RectF>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellRects.clear()
        val cellWidth = w / 4f
        for (i in 0 until 4) {
            cellRects.add(RectF(i * cellWidth, 0f, (i + 1) * cellWidth, h.toFloat()))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellRects.size < 4) return

        drawGear(canvas, cellRects[0])
        drawGlyphLabel(canvas, cellRects[1], AynehaGlyphs.SYMBOLS_SWITCH)
        drawMountain(canvas, cellRects[2])
        drawGlyphLabel(canvas, cellRects[3], AynehaGlyphs.ALPHABET_SWITCH)
    }

    private fun drawGlyphLabel(canvas: Canvas, rect: RectF, text: String) {
        val fm = textPaint.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, rect.centerX(), baseline, textPaint)
    }

    /** Simple cog: a ring with short radial teeth — settings icon. */
    private fun drawGear(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val r = rect.height() * 0.16f
        canvas.drawCircle(cx, cy, r * 0.42f, strokePaint)
        canvas.drawCircle(cx, cy, r, strokePaint)
        val teeth = 8
        val toothLen = r * 0.55f
        for (i in 0 until teeth) {
            val angle = (Math.PI * 2 * i / teeth)
            val x1 = cx + (r * Math.cos(angle)).toFloat()
            val y1 = cy + (r * Math.sin(angle)).toFloat()
            val x2 = cx + ((r + toothLen) * Math.cos(angle)).toFloat()
            val y2 = cy + ((r + toothLen) * Math.sin(angle)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
        }
    }

    /** Two overlapping peaks — numeric-pad switch icon (matches the mockup's mountain glyph). */
    private fun drawMountain(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val halfW = rect.height() * 0.30f
        val halfH = rect.height() * 0.20f
        val baseY = cy + halfH * 0.5f

        val backPeak = Path()
        backPeak.moveTo(cx - halfW * 0.15f, baseY)
        backPeak.lineTo(cx + halfW * 0.55f, cy - halfH)
        backPeak.lineTo(cx + halfW * 1.15f, baseY)
        canvas.drawPath(backPeak, strokePaint)

        val frontPeak = Path()
        frontPeak.moveTo(cx - halfW * 1.15f, baseY)
        frontPeak.lineTo(cx - halfW * 0.35f, cy - halfH * 0.35f)
        frontPeak.lineTo(cx + halfW * 0.45f, baseY)
        canvas.drawPath(frontPeak, strokePaint)

        canvas.drawLine(cx - halfW * 1.15f, baseY, cx + halfW * 1.15f, baseY, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val index = cellRects.indexOfFirst { it.contains(event.x, event.y) }
            if (index >= 0) {
                if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                when (index) {
                    0 -> onSettings?.invoke()
                    1 -> onSwitchView?.invoke("symbols_page1")
                    2 -> onSwitchView?.invoke("numpad")
                    3 -> onSwitchView?.invoke("alphabet")
                }
            }
        }
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
