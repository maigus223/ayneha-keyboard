// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 MAIGUS

package com.ayneha.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.ContextCompat

/**
 * Renders one [LayoutView] of the AYNEHA keyboard.
 *
 * Placement rule (from the spec): keys[0] of a row sits at the extreme RIGHT edge of the
 * keyboard, keys[last] at the extreme LEFT edge — i.e. the array is walked in order while
 * keys are placed right-to-left across the row. See [layoutCurrentRows].
 */
class AynehaKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var actionListener: KeyboardActionListener? = null
    var hapticEnabled: Boolean = true

    init {
        // layoutCurrentRows() below already implements the RTL key order manually (keys[0] at
        // the right edge, walking leftwards). If the system/host locale is itself RTL, Android
        // could otherwise mirror this view's layout a second time and undo our placement — so
        // this view's own direction is pinned to LTR regardless of locale/host container.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }

    private var config: KeyboardConfig? = null
    var currentViewId: String = "alphabet"
        private set

    private val rowRects = ArrayList<ArrayList<Pair<KeyDef, RectF>>>()
    /** Pinned bottom bar (switch/virgule/emoji/espace/point/entrée) — laid out separately from
     *  [rowRects] so it never scrolls away with the content above it. */
    private var bottomBarKeys = ArrayList<Pair<KeyDef, RectF>>()
    private var bottomBarTop = 0f

    private val typeface: Typeface? =
        ResourcesCompat.getFont(context, R.font.ayneha_pro)

    private val keyBgDefault = ContextCompat.getColor(context, R.color.key_bg_default)
    private val keyBgAction = ContextCompat.getColor(context, R.color.key_bg_action)
    private val keyBgPressed = ContextCompat.getColor(context, R.color.key_bg_pressed)
    private val keyBgSpecial = ContextCompat.getColor(context, R.color.key_bg_special)
    private val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val textOnSpecial = ContextCompat.getColor(context, R.color.text_on_special)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@AynehaKeyboardView.typeface
        textAlign = Paint.Align.CENTER
    }
    // Backspace/enter are drawn as hand-built vector icons (see drawBackspaceIcon /
    // drawEnterIcon) rather than as text glyphs: the AYNEHA font only
    // covers letters/digits/punctuation, so arrow-style icon codepoints would silently fall
    // back to whatever glyph (or "tofu" placeholder) the device's system font happens to have,
    // which looks inconsistent or broken from device to device. Drawing the icon ourselves
    // guarantees the exact same shape everywhere.
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2.4f)
    }
    private val keyPadding = dp(3f)
    private val cornerRadius = dp(12f) // matches design token --radius: 0.75rem (12px)

    companion object {
        /** Fixed height of the pinned bottom bar (switch/virgule/emoji/espace/point/entrée). */
        private const val BOTTOM_BAR_HEIGHT_DP = 64f
        /** Content rows never shrink below this — pages with few rows (alphabet, numpad) grow
         *  to fill the space instead. Tuned so the mockup's 5-row emoji grid fits with zero
         *  scrolling ("toutes les touches visibles"), while staying a comfortable tap target. */
        private const val MIN_CONTENT_ROW_HEIGHT_DP = 40f
    }

    private var pressedKey: KeyDef? = null
    private var pressedRect: RectF? = null

    // --- vertical scrolling (used by pages with more rows than fit on screen, e.g. emoji) ---
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var scrollOffsetY = 0f
    private var maxScrollY = 0f
    private var isScrollable = false
    private var isScrolling = false
    private var touchDownY = 0f
    private var lastTouchY = 0f

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private var longPressRunnable: Runnable? = null

    // --- key repeat (backspace) ---
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    // --- long-press variant popup ---
    private var popupWindow: PopupWindow? = null
    private var popupVariants: List<KeyVariant> = emptyList()
    private var popupSelectedIndex: Int = -1
    private var popupItemViews: List<TextView> = emptyList()

    fun setConfig(cfg: KeyboardConfig, initialView: String = "alphabet") {
        config = cfg
        currentViewId = initialView
        scrollOffsetY = 0f
        requestLayoutRows()
    }

    fun switchToView(viewId: String) {
        if (config?.view(viewId) == null) return
        currentViewId = viewId
        scrollOffsetY = 0f
        requestLayoutRows()
    }

    private fun requestLayoutRows() {
        if (width > 0 && height > 0) layoutCurrentRows(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutCurrentRows(w, h)
    }

    private fun layoutCurrentRows(w: Int, h: Int) {
        rowRects.clear()
        bottomBarKeys.clear()
        val view = config?.view(currentViewId) ?: return
        val rows = view.contentRows
        if (w == 0 || h == 0) return

        // Bottom bar (switch/virgule/emoji/espace/point/entrée) is pinned: it always gets the
        // same fixed height at the bottom of the view and is laid out outside the scrollable
        // content area, so it stays visible even on pages with lots of rows (e.g. "emoji").
        val bottomBarHeight = if (view.bottomBar != null) dp(BOTTOM_BAR_HEIGHT_DP) else 0f
        bottomBarTop = h - bottomBarHeight
        view.bottomBar?.let { bar -> layoutRow(bar, w, bottomBarTop, h.toFloat(), bottomBarKeys) }

        if (rows.isEmpty()) return

        // Content rows fill the remaining space above the bottom bar. A page with few rows
        // (alphabet, symbols, numpad) grows its rows to fill that space; a page with many rows
        // (emoji) is capped at a comfortable minimum tap-target height and becomes scrollable
        // instead of shrinking keys down to nothing.
        val contentAreaHeight = bottomBarTop
        val naturalRowHeight = contentAreaHeight / rows.size
        val rowHeight = naturalRowHeight.coerceAtLeast(dp(MIN_CONTENT_ROW_HEIGHT_DP))
        val contentHeight = rowHeight * rows.size
        maxScrollY = (contentHeight - contentAreaHeight).coerceAtLeast(0f)
        isScrollable = maxScrollY > 0f
        scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScrollY)

        for ((ri, row) in rows.withIndex()) {
            val top = ri * rowHeight
            val bottom = (ri + 1) * rowHeight
            val keyList = ArrayList<Pair<KeyDef, RectF>>()
            layoutRow(row, w, top, bottom, keyList)
            rowRects.add(keyList)
        }
    }

    /** [RowDef.mirror] true: keys[0] -> extreme right edge, walking left (AYNEHA letters).
     *  [RowDef.mirror] false: keys[0] -> extreme left edge, walking right (digits/symbols/
     *  emoji — read the same regardless of script direction, per the mockups). */
    private fun layoutRow(row: RowDef, w: Int, top: Float, bottom: Float, out: ArrayList<Pair<KeyDef, RectF>>) {
        val totalWeight = row.keys.sumOf { it.width }
        if (row.mirror) {
            var xRight = w.toFloat()
            for (key in row.keys) {
                val keyWidth = (key.width / totalWeight).toFloat() * w
                val left = xRight - keyWidth
                val rect = RectF(left + keyPadding, top + keyPadding, xRight - keyPadding, bottom - keyPadding)
                out.add(key to rect)
                xRight = left
            }
        } else {
            var xLeft = 0f
            for (key in row.keys) {
                val keyWidth = (key.width / totalWeight).toFloat() * w
                val right = xLeft + keyWidth
                val rect = RectF(xLeft + keyPadding, top + keyPadding, right - keyPadding, bottom - keyPadding)
                out.add(key to rect)
                xLeft = right
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val needsClip = isScrollable
        if (needsClip) {
            canvas.save()
            // Clip to the content area only (above the pinned bottom bar) so scrolled content
            // never bleeds under/over the fixed bar.
            canvas.clipRect(0f, 0f, width.toFloat(), bottomBarTop)
            canvas.translate(0f, -scrollOffsetY)
        }
        for (row in rowRects) {
            for ((key, rect) in row) {
                drawKey(canvas, key, rect)
            }
        }
        if (needsClip) canvas.restore()

        // Bottom bar is drawn last, unclipped/untranslated — always pinned in place.
        for ((key, rect) in bottomBarKeys) {
            drawKey(canvas, key, rect)
        }
    }

    private fun drawKey(canvas: Canvas, key: KeyDef, rect: RectF) {
        val isPressed = key === pressedKey
        val isEnter = key.action == "enter"
        keyPaint.color = when {
            isPressed -> keyBgPressed
            isEnter -> keyBgSpecial
            key.keyType == KeyType.ACTION || key.keyType == KeyType.SWITCH_VIEW -> keyBgAction
            else -> keyBgDefault
        }

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)

        val iconColor = if (isEnter) textOnSpecial else textPrimary
        when (key.action) {
            "backspace" -> {
                iconPaint.color = iconColor
                drawBackspaceIcon(canvas, rect, iconPaint)
                return
            }
            "enter" -> {
                iconPaint.color = iconColor
                drawEnterIcon(canvas, rect, iconPaint)
                return
            }
        }

        textPaint.color = if (isEnter) textOnSpecial else textPrimary
        // Sizing rule for glyphs on keycaps:
        // - space label: doubled from its original small size for legibility.
        // - the numpad-switch key ("=\<") is a 3-glyph label that overflowed at the standard
        //   icon size, so it's rendered at half that size.
        // - every other key (action/switch icons, and regular letters/digits/standard
        //   international characters) is drawn twice the original base size for legibility.
        val isNumpadSwitch = key.keyType == KeyType.SWITCH_VIEW && key.targetView == "numpad"
        val isEmojiGrid = currentViewId == "emoji" && key.keyType == KeyType.CHAR
        textPaint.textSize = when {
            key.keyType == KeyType.SPACE -> dp(24f)
            isNumpadSwitch -> dp(16.2f)
            isEmojiGrid -> dp(24f)
            else -> dp(36f)
        }

        val glyph = key.displayGlyph()
        val fm = textPaint.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        if (key.mirror) {
            // Effet miroir horizontal (ex. apostrophe : la queue doit pointer vers la droite),
            // même principe que drawBackspaceIcon — on ne retourne que le glyphe, pas la touche.
            canvas.save()
            canvas.scale(-1f, 1f, rect.centerX(), rect.centerY())
            canvas.drawText(glyph, rect.centerX(), baseline, textPaint)
            canvas.restore()
        } else {
            canvas.drawText(glyph, rect.centerX(), baseline, textPaint)
        }

        // Small dot indicator for keys that have long-press variants.
        if (key.hasVariants) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textPrimary; alpha = 110 }
            canvas.drawCircle(rect.right - dp(5f), rect.top + dp(6f), dp(1.6f), dotPaint)
        }
    }

    /** Pointed "erase" keycap (rectangle with an arrow-point on the right) with an X inside —
     *  points rightward to match the keyboard's RTL erase direction. */
    private fun drawBackspaceIcon(canvas: Canvas, rect: RectF, paint: Paint) {
        val padX = rect.width() * 0.24f
        val padY = rect.height() * 0.28f
        val l = rect.left + padX
        val r = rect.right - padX
        val t = rect.top + padY
        val b = rect.bottom - padY
        val midY = (t + b) / 2f
        val point = (b - t) * 0.4f

        val outline = Path()
        outline.moveTo(l, t)
        outline.lineTo(r - point, t)
        outline.lineTo(r, midY)
        outline.lineTo(r - point, b)
        outline.lineTo(l, b)
        outline.close()
        canvas.drawPath(outline, paint)

        val xInset = (r - point - l) * 0.26f
        val x1 = l + xInset
        val x2 = (r - point) - xInset
        val y1 = t + (b - t) * 0.26f
        val y2 = b - (b - t) * 0.26f
        canvas.drawLine(x1, y1, x2, y2, paint)
        canvas.drawLine(x2, y1, x1, y2, paint)
    }

    /** "Return" icon: a stem that drops down then hooks rightward with an arrowhead — points
     *  rightward to match the keyboard's RTL reading direction. */
    private fun drawEnterIcon(canvas: Canvas, rect: RectF, paint: Paint) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val halfW = rect.width() * 0.20f
        val halfH = rect.height() * 0.22f
        val stemX = cx - halfW * 0.6f
        val topY = cy - halfH
        val bottomY = cy + halfH * 0.5f
        val rightX = cx + halfW

        val path = Path()
        path.moveTo(stemX, topY)
        path.lineTo(stemX, bottomY)
        path.lineTo(rightX, bottomY)
        canvas.drawPath(path, paint)

        val ah = halfH * 0.6f
        val arrow = Path()
        arrow.moveTo(rightX - ah, bottomY - ah)
        arrow.lineTo(rightX, bottomY)
        arrow.lineTo(rightX - ah, bottomY + ah)
        canvas.drawPath(arrow, paint)
    }

    // ---------------------------------------------------------------------
    // Touch handling
    // ---------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP -> handleUp(event)
            MotionEvent.ACTION_CANCEL -> handleCancel()
        }
        return true
    }

    /** [x],[y] are view-space touch coordinates; converted to content-space before hit-testing. */
    private fun findKeyAt(x: Float, y: Float): Pair<KeyDef, RectF>? {
        // Bottom bar is pinned (not scrolled/clipped), so it's tested directly in view-space.
        if (y >= bottomBarTop) {
            for ((key, rect) in bottomBarKeys) {
                val hitRect = RectF(rect.left - keyPadding, rect.top - keyPadding, rect.right + keyPadding, rect.bottom + keyPadding)
                if (hitRect.contains(x, y)) return key to rect
            }
            return null
        }
        val contentY = y + scrollOffsetY
        for (row in rowRects) {
            for ((key, rect) in row) {
                val hitRect = RectF(rect.left - keyPadding, rect.top - keyPadding, rect.right + keyPadding, rect.bottom + keyPadding)
                if (hitRect.contains(x, contentY)) return key to rect
            }
        }
        return null
    }

    private fun handleDown(event: MotionEvent) {
        touchDownY = event.y
        lastTouchY = event.y
        isScrolling = false

        val hit = findKeyAt(event.x, event.y) ?: return
        val (key, rect) = hit
        pressedKey = key
        pressedRect = rect
        longPressTriggered = false
        invalidate()
        performKeyHaptic()

        if (key.hasVariants) {
            longPressRunnable = Runnable {
                longPressTriggered = true
                showVariantPopup(key, rect)
            }
            longPressHandler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
        } else if (key.action == "backspace") {
            scheduleBackspaceRepeat(key)
        }
    }

    private fun handleMove(event: MotionEvent) {
        if (longPressTriggered) {
            updatePopupSelection(event.rawX)
            return
        }

        if (isScrollable && touchDownY < bottomBarTop) {
            if (!isScrolling && kotlin.math.abs(event.y - touchDownY) > touchSlop) {
                // Drag exceeded the tap threshold: treat as a scroll gesture instead of a tap.
                isScrolling = true
                cancelBackspaceRepeat()
                cancelKeyLongPress()
                pressedKey = null
                pressedRect = null
            }
            if (isScrolling) {
                val deltaY = lastTouchY - event.y
                lastTouchY = event.y
                scrollOffsetY = (scrollOffsetY + deltaY).coerceIn(0f, maxScrollY)
                invalidate()
                return
            }
        }

        // If finger drags off the currently pressed key, cancel the press (but keep repeat
        // logic simple: only cancel long-press timer, repeat continues only while on key).
        val hit = findKeyAt(event.x, event.y)
        if (hit == null || hit.first !== pressedKey) {
            cancelKeyLongPress()
        }
    }

    private fun handleUp(event: MotionEvent) {
        cancelBackspaceRepeat()
        if (isScrolling) {
            isScrolling = false
            pressedKey = null
            pressedRect = null
            invalidate()
            return
        }
        val key = pressedKey
        if (longPressTriggered) {
            commitPopupSelection()
        } else if (key != null) {
            // Re-validate finger is still over the key it started on.
            val hit = findKeyAt(event.x, event.y)
            if (hit != null && hit.first === key) {
                dispatchTap(key)
            }
        }
        cancelKeyLongPress()
        pressedKey = null
        pressedRect = null
        invalidate()
    }

    private fun handleCancel() {
        cancelBackspaceRepeat()
        cancelKeyLongPress()
        hidePopup()
        isScrolling = false
        pressedKey = null
        pressedRect = null
        invalidate()
    }

    private fun dispatchTap(key: KeyDef) {
        when (key.keyType) {
            KeyType.SWITCH_VIEW -> key.targetView?.let {
                switchToView(it)
                actionListener?.onSwitchView(it)
            }
            KeyType.ACTION -> key.action?.let { actionListener?.onAction(it) }
            KeyType.SPACE, KeyType.CHAR -> {
                val t = key.text()
                if (t.isNotEmpty()) actionListener?.onText(t)
            }
        }
    }

    private fun cancelKeyLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
        if (longPressTriggered) hidePopup()
        longPressTriggered = false
    }

    private fun performKeyHaptic() {
        if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // ---------------------------------------------------------------------
    // Backspace repeat-while-held
    // ---------------------------------------------------------------------

    private fun scheduleBackspaceRepeat(key: KeyDef) {
        repeatRunnable = object : Runnable {
            override fun run() {
                actionListener?.onAction("backspace")
                repeatHandler.postDelayed(this, 45L)
            }
        }
        repeatHandler.postDelayed(repeatRunnable!!, 400L)
    }

    private fun cancelBackspaceRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    // ---------------------------------------------------------------------
    // Long-press variant popup
    // ---------------------------------------------------------------------

    /**
     * Bubble shows ONLY the variant glyphs — the base key is not repeated in the popup.
     * Container uses RTL layout direction so item order in the array (Double → Muet /
     * Double → Nasalisé → Ouvert, per spec) reads correctly right-to-left, consistent
     * with the rest of the keyboard.
     */
    private fun showVariantPopup(key: KeyDef, anchorRect: RectF) {
        popupVariants = key.longPressVariants
        if (popupVariants.isEmpty()) return
        popupSelectedIndex = 0

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(ContextCompat.getColor(context, R.color.popup_bg))
            setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
        }

        val itemViews = ArrayList<TextView>()
        for (variant in popupVariants) {
            val tv = TextView(context).apply {
                text = variant.text()
                typeface = this@AynehaKeyboardView.typeface
                textSize = 40f
                setTextColor(ContextCompat.getColor(context, R.color.popup_text))
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            }
            itemViews.add(tv)
            container.addView(tv)
        }
        itemViews.firstOrNull()?.setBackgroundColor(ContextCompat.getColor(context, R.color.popup_selected_bg))
        popupItemViews = itemViews

        val popup = PopupWindow(container, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        popup.isTouchable = false
        popup.isOutsideTouchable = false
        popupWindow = popup

        val loc = IntArray(2)
        this.getLocationInWindow(loc)
        val x = (loc[0] + anchorRect.centerX()).toInt()
        val y = loc[1] + anchorRect.top.toInt()
        // showAtLocation with a rough estimate; popup measures itself after layout.
        popup.showAtLocation(this, android.view.Gravity.NO_GRAVITY, x - dp(40f).toInt(), y - dp(60f).toInt())
    }

    private fun updatePopupSelection(rawX: Float) {
        val views = popupItemViews
        if (views.isEmpty()) return
        var closestIndex = 0
        var closestDist = Float.MAX_VALUE
        for ((i, v) in views.withIndex()) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val centerX = loc[0] + v.width / 2f
            val dist = kotlin.math.abs(rawX - centerX)
            if (dist < closestDist) {
                closestDist = dist
                closestIndex = i
            }
        }
        if (closestIndex != popupSelectedIndex) {
            views.getOrNull(popupSelectedIndex)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            views.getOrNull(closestIndex)?.setBackgroundColor(ContextCompat.getColor(context, R.color.popup_selected_bg))
            popupSelectedIndex = closestIndex
        }
    }

    private fun commitPopupSelection() {
        val variant = popupVariants.getOrNull(popupSelectedIndex)
        hidePopup()
        if (variant != null) actionListener?.onText(variant.text())
    }

    private fun hidePopup() {
        popupWindow?.dismiss()
        popupWindow = null
        popupItemViews = emptyList()
        popupVariants = emptyList()
        popupSelectedIndex = -1
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
