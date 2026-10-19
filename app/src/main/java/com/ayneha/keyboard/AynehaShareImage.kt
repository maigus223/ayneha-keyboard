// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 MAIGUS

package com.ayneha.keyboard

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateFormat
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Date

/**
 * Turns whatever is currently typed in an AYNEHA composition zone into a shareable .jpg,
 * so it displays correctly (WhatsApp, Instagram, SMS, anywhere) even for a recipient with
 * neither the AYNEHA font nor the AYNEHA keyboard installed — the PUA codepoints never
 * leave the device as text, only as pixels.
 *
 * The exported image has three parts, stacked top to bottom:
 *  - Header: "AYNEHA" in Latin, then the same word spelled in real AYNEHA glyphs (the exact
 *    same codepoint sequence used on the keyboard's own space bar, AynehaGlyphs.SPACE_LABEL),
 *    in a warm orange for visibility.
 *  - Body: the composition zone's own Layout (exact glyph shapes/positions already on
 *    screen, bidi-correct), cropped to its actual text height rather than a full-view
 *    screenshot.
 *  - Footer: the user's optional pseudo (AynehaPrefs.KEY_PSEUDO) and/or a timestamp
 *    (toggled independently via AynehaPrefs.KEY_TIMESTAMP_ENABLED) — either, both, or
 *    neither, entirely independent of one another.
 */
object AynehaShareImage {

    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val JPEG_QUALITY = 92

    private const val SIDE_PADDING_DP = 32f
    private const val HEADER_TOP_PADDING_DP = 28f
    private const val HEADER_TEXT_SIZE_DP = 30f
    private const val DIVIDER_GAP_DP = 20f
    private const val DIVIDER_HEIGHT_DP = 2f
    private const val BODY_TOP_GAP_DP = 24f
    private const val FOOTER_BOTTOM_PADDING_DP = 24f
    private const val FOOTER_LINE_GAP_DP = 8f
    private const val PSEUDO_TEXT_SIZE_DP = 20f
    private const val TIMESTAMP_TEXT_SIZE_DP = 17f

    private val WARM_ORANGE = Color.parseColor("#FF6600")
    private val HEADER_LATIN_COLOR = Color.parseColor("#141414")
    private val DIVIDER_COLOR = Color.parseColor("#DCDCDC")
    private val TIMESTAMP_COLOR = Color.parseColor("#8C8C8C")

    fun share(context: Context, editText: EditText) {
        val text = editText.text
        if (text.isNullOrBlank()) {
            Toast.makeText(context, R.string.share_empty_warning, Toast.LENGTH_SHORT).show()
            return
        }

        val layout = editText.layout ?: return
        val bitmap = renderToBitmap(context, editText, layout)
        val file = saveToCache(context, bitmap)
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, context.getString(R.string.share_chooser_title))
        )
    }

    private fun renderToBitmap(context: Context, editText: EditText, layout: android.text.Layout): Bitmap {
        val density = context.resources.displayMetrics.density
        fun px(dpValue: Float) = dpValue * density

        val ayneha = try {
            androidx.core.content.res.ResourcesCompat.getFont(context, R.font.ayneha_pro)
        } catch (e: Exception) {
            null
        }

        val headerLatinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = px(HEADER_TEXT_SIZE_DP)
            color = HEADER_LATIN_COLOR
        }
        val headerAynehaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ayneha ?: Typeface.DEFAULT
            textSize = px(HEADER_TEXT_SIZE_DP)
            color = WARM_ORANGE
        }
        val pseudoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = px(PSEUDO_TEXT_SIZE_DP)
            color = WARM_ORANGE
        }
        val timestampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = px(TIMESTAMP_TEXT_SIZE_DP)
            color = TIMESTAMP_COLOR
        }

        val headerLatin = "AYNEHA   -   "
        val headerAyneha = AynehaGlyphs.SPACE_LABEL

        val pseudo = AynehaPrefs.getPseudo(context).trim()
        val showPseudo = pseudo.isNotEmpty()
        val showTimestamp = AynehaPrefs.isTimestampEnabled(context)
        val timestampText = if (showTimestamp) {
            DateFormat.format("dd/MM/yyyy  HH:mm:ss", Date()).toString()
        } else ""

        val sidePadding = px(SIDE_PADDING_DP)
        val width = editText.width

        // --- Vertical layout math ---
        val headerFm = headerLatinPaint.fontMetrics
        val headerHeight = headerFm.descent - headerFm.ascent
        val dividerHeight = px(DIVIDER_HEIGHT_DP)

        val bodyTextHeight = layout.getLineBottom(layout.lineCount - 1)

        var footerHeight = 0f
        val pseudoFm = pseudoPaint.fontMetrics
        val timestampFm = timestampPaint.fontMetrics
        if (showPseudo) footerHeight += (pseudoFm.descent - pseudoFm.ascent)
        if (showPseudo && showTimestamp) footerHeight += px(FOOTER_LINE_GAP_DP)
        if (showTimestamp) footerHeight += (timestampFm.descent - timestampFm.ascent)
        val hasFooter = showPseudo || showTimestamp

        val totalHeight = (px(HEADER_TOP_PADDING_DP) + headerHeight +
            px(DIVIDER_GAP_DP) + dividerHeight + px(BODY_TOP_GAP_DP) +
            editText.extendedPaddingTop + bodyTextHeight + editText.compoundPaddingBottom +
            (if (hasFooter) px(DIVIDER_GAP_DP) + dividerHeight + px(DIVIDER_GAP_DP) else 0f) +
            footerHeight +
            (if (hasFooter) px(FOOTER_BOTTOM_PADDING_DP) else px(BODY_TOP_GAP_DP)))
            .toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = px(HEADER_TOP_PADDING_DP) - headerFm.ascent
        canvas.drawText(headerLatin, sidePadding, y, headerLatinPaint)
        val latinWidth = headerLatinPaint.measureText(headerLatin)
        canvas.drawText(headerAyneha, sidePadding + latinWidth, y, headerAynehaPaint)

        val lineY = px(HEADER_TOP_PADDING_DP) + headerHeight + px(DIVIDER_GAP_DP)
        canvas.drawRect(sidePadding, lineY, width - sidePadding, lineY + dividerHeight, Paint().apply { color = DIVIDER_COLOR })

        canvas.save()
        canvas.translate(
            editText.compoundPaddingLeft.toFloat(),
            lineY + dividerHeight + px(BODY_TOP_GAP_DP) + editText.extendedPaddingTop
        )
        layout.draw(canvas)
        canvas.restore()

        if (hasFooter) {
            val footerLineY = lineY + dividerHeight + px(BODY_TOP_GAP_DP) +
                editText.extendedPaddingTop + bodyTextHeight + editText.compoundPaddingBottom + px(DIVIDER_GAP_DP)
            canvas.drawRect(sidePadding, footerLineY, width - sidePadding, footerLineY + dividerHeight, Paint().apply { color = DIVIDER_COLOR })

            var fy = footerLineY + dividerHeight + px(DIVIDER_GAP_DP)
            if (showPseudo) {
                fy -= pseudoFm.ascent
                canvas.drawText(pseudo, sidePadding, fy, pseudoPaint)
                fy += pseudoFm.descent + px(FOOTER_LINE_GAP_DP)
            }
            if (showTimestamp) {
                fy -= timestampFm.ascent
                canvas.drawText(timestampText, sidePadding, fy, timestampPaint)
            }
        }

        return bitmap
    }

    private fun saveToCache(context: Context, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
        val file = File(dir, "ayneha_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        return file
    }
}
