// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 MAIGUS

package com.ayneha.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model mirroring ayneha_keyboard_config.json (layout_views section).
 *
 * IMPORTANT — rendering rule from the spec:
 * "Render items in array from RIGHT to LEFT on screen." i.e. keys[0] is drawn at the
 * extreme RIGHT edge of the keyboard, keys[last] at the extreme LEFT edge.
 * See AynehaKeyboardView.layoutRow() for the actual placement logic.
 */

enum class KeyType { CHAR, ACTION, SWITCH_VIEW, SPACE }

data class KeyVariant(
    val label: String,
    val pua: String?
) {
    /** The actual character(s) this variant inserts. */
    fun text(): String = pua?.let { wrapRtl(puaToString(it)) } ?: label
}

data class KeyDef(
    val keyType: KeyType,
    val action: String? = null,          // "backspace" | "enter"
    val label: String,
    val pua: String? = null,
    val unicode: String? = null,
    val targetView: String? = null,      // for SWITCH_VIEW
    val width: Double = 1.0,
    val longPressVariants: List<KeyVariant> = emptyList(),
    val mirror: Boolean = false          // true: keycap glyph drawn horizontally flipped
                                          // (ex. apostrophe with its tail turned to the right)
) {
    /** The character(s) this key inserts when tapped (empty for pure action/switch keys). */
    fun text(): String = when (keyType) {
        KeyType.CHAR -> pua?.let { wrapRtl(puaToString(it)) } ?: unicode?.let { puaToString(it) } ?: label
        KeyType.SPACE -> unicode?.let { puaToString(it) } ?: " "
        KeyType.ACTION -> unicode?.let { puaToString(it) } ?: ""
        KeyType.SWITCH_VIEW -> ""
    }

    /** Displayed glyph on the keycap itself. Falls back to the plain label. */
    fun displayGlyph(): String = when {
        // Mode-switch keys get their AYNEHA glyph rendering (see AynehaGlyphs).
        keyType == KeyType.SWITCH_VIEW && targetView == "symbols_page1" -> AynehaGlyphs.SYMBOLS_SWITCH
        keyType == KeyType.SWITCH_VIEW && targetView == "alphabet" -> AynehaGlyphs.ALPHABET_SWITCH
        keyType == KeyType.CHAR && pua != null -> puaToString(pua)
        keyType == KeyType.CHAR && unicode != null -> puaToString(unicode)
        // Space bar keycap: "AYNEHA" spelled out in real AYNEHA glyphs (see AynehaGlyphs).
        keyType == KeyType.SPACE -> AynehaGlyphs.SPACE_LABEL
        else -> label
    }

    val hasVariants: Boolean get() = longPressVariants.isNotEmpty()
}

/**
 * [mirror] true (default) places keys[0] at the extreme RIGHT edge, walking left — used for
 * rows of actual AYNEHA letters, matching the writing system's right-to-left reading order.
 * [mirror] false places keys[0] at the extreme LEFT edge, walking right (natural reading
 * order) — used for digits/punctuation/symbols/emoji rows, which read the same regardless of
 * script direction (confirmed against the mockups: "1 2 3 ÷" reads left-to-right, not mirrored).
 */
data class RowDef(val rowIndex: Int, val keys: List<KeyDef>, val mirror: Boolean = true)

/**
 * [contentRows] are the scrollable/main key rows for this page (letters, digits, emoji…).
 * [bottomBar] is the row containing the SPACE key (switch / virgule / emoji / espace / point /
 * entrée) — it is pinned at the bottom of the keyboard and never scrolls away, matching the
 * mockup where this bar stays fixed under every page (LETTRES/SYMBOLES/NUMERIQUE/EMOJI).
 */
data class LayoutView(
    val id: String,
    val title: String,
    val contentRows: List<RowDef>,
    val bottomBar: RowDef?
) {
    /** Back-compat convenience: every row, bottom bar last, for callers that don't care. */
    val rows: List<RowDef> get() = bottomBar?.let { contentRows + it } ?: contentRows
}

data class KeyboardConfig(val views: Map<String, LayoutView>) {
    fun view(id: String): LayoutView? = views[id]
}

/** Converts a "U+E00B" style codepoint string into a Kotlin String. */
fun puaToString(codepoint: String): String {
    return try {
        val clean = codepoint.removePrefix("U+").removePrefix("u+")
        val cp = clean.toInt(16)
        String(Character.toChars(cp))
    } catch (e: NumberFormatException) {
        codepoint
    }
}

/**
 * AYNEHA letters/digits/punctuation are custom Private-Use-Area codepoints. PUA codepoints
 * have no defined Unicode bidi class, so the Unicode Bidi Algorithm defaults them to strongly
 * Left-to-Right — meaning a naive commit would make typed words grow to the right, and
 * backspace would feel like it's erasing from the wrong end.
 *
 * Wrapping every inserted AYNEHA glyph in an explicit Right-to-Left Override (U+202E ... U+202C)
 * forces it to be treated as RTL for display, so consecutive glyphs reorder into one right-to-left
 * run: typed text grows from right to left and backspace removes the most recently typed
 * (leftmost) character — matching how Arabic or N'Ko keyboards behave. See [AynehaKeyboardEditing]
 * for the matching backspace-side unwrap logic.
 */
fun wrapRtl(text: String): String = "${AynehaKeyboardEditing.RLO}$text${AynehaKeyboardEditing.PDF}"

/** Shared constants/helpers for detecting and removing the RTL-override wrapper on backspace. */
object AynehaKeyboardEditing {
    const val RLO = '\u202E' // Right-to-Left Override
    const val PDF = '\u202C' // Pop Directional Formatting

    /** Max UTF-16 units a wrapper can span: RLO + surrogate pair + PDF. Ask for this many before the cursor. */
    const val MAX_WRAPPER_UNITS = 4

    /**
     * Given the (up to 4) UTF-16 units immediately before the cursor, returns how many of them
     * should be deleted for one logical backspace:
     *  - 4 for a plane-16 glyph wrapper we produced: RLO + high surrogate + low surrogate + PDF
     *    (AYNEHA now lives at U+10FF7C-U+10FFDD, which takes 2 UTF-16 units);
     *  - 3 for a legacy BMP wrapper: RLO + 1 unit + PDF (old U+E000-E061 text);
     *  - 2 for a bare surrogate pair (e.g. an emoji committed without wrapper), so a pair is
     *    never split in half;
     *  - 1 otherwise (a normal, unwrapped character).
     */
    fun backspaceLength(textBeforeCursor: CharSequence): Int {
        val n = textBeforeCursor.length
        if (n >= 4 &&
            textBeforeCursor[n - 1] == PDF &&
            Character.isLowSurrogate(textBeforeCursor[n - 2]) &&
            Character.isHighSurrogate(textBeforeCursor[n - 3]) &&
            textBeforeCursor[n - 4] == RLO
        ) return 4
        if (n >= 3 &&
            textBeforeCursor[n - 1] == PDF &&
            textBeforeCursor[n - 3] == RLO
        ) return 3
        if (n >= 2 &&
            Character.isLowSurrogate(textBeforeCursor[n - 1]) &&
            Character.isHighSurrogate(textBeforeCursor[n - 2])
        ) return 2
        return 1
    }

    /**
     * Number of UTF-16 units of the logical unit that STARTS at [index] in [text]:
     * a complete RLO-glyph-PDF wrapper (3 or 4 units), a bare surrogate pair (2), else 1.
     */
    fun unitLengthAt(text: CharSequence, index: Int): Int {
        val n = text.length
        if (index >= n) return 1
        if (text[index] == RLO && index + 1 < n) {
            val glyphUnits = if (Character.isHighSurrogate(text[index + 1]) && index + 2 < n &&
                Character.isLowSurrogate(text[index + 2])) 2 else 1
            val end = index + 1 + glyphUnits
            if (end < n && text[end] == PDF) return glyphUnits + 2
            return 1
        }
        if (Character.isHighSurrogate(text[index]) && index + 1 < n &&
            Character.isLowSurrogate(text[index + 1])) return 2
        return 1
    }
}

/** AYNEHA glyph sequences for the mode-switch keys, built from PUA vowel/numeral codes.
 *
 * These keys are rendered with actual AYNEHA glyphs instead of plain Latin text so the
 * keyboard stays visually consistent with the writing system. Because unassigned/PUA
 * codepoints default to a Left-to-Right bidi class, a naive concatenation renders in the
 * wrong order inside our RTL keyboard. We therefore build the string in reverse so the
 * final left-to-right draw call produces the correct RTL *visual* order.
 */
object AynehaGlyphs {
    // "؟321" (bascule vers la page symboles) : le "؟" n'a pas d'équivalent AYNEHA dans la
    // matrice (pas de glyphe dédié), il reste tel quel. Les chiffres 3, 2, 1 passent en
    // glyphes AYNEHA réels. Ordre conservé identique au libellé source ("؟" puis 3 puis 2
    // puis 1) — AUCUNE inversion, contrairement à SPACE_LABEL ci-dessous (cf. spec §7.1).
    val SYMBOLS_SWITCH: String = "؟" + listOf("U+10FF7F", "U+10FF7E", "U+10FF7D")
        .joinToString("") { puaToString(it) }

    // "IEA" (bascule retour vers l'alphabet) : glyphes AYNEHA I, E, A, même ordre que le
    // libellé source, aucune inversion (cf. spec §7.1).
    val ALPHABET_SWITCH: String = listOf("U+10FF9E", "U+10FF9D", "U+10FF9C")
        .joinToString("") { puaToString(it) }

    // "AYNEHA" (touche ESPACE) : séquence exacte de codepoints fournie dans la spécification
    // (spec §7.2) — A, Y(muet), N, E, A, H, dans cet ordre logique. Affichage EXIGÉ de droite
    // à gauche : le premier codepoint de la liste (A) doit apparaître à l'extrême droite du
    // libellé. Comme le tracé canvas dessine la chaîne littéralement de gauche à droite (les
    // glyphes PUA n'ont pas de classe bidi propre), on stocke la liste inversée pour que le
    // tracé LTR reproduise l'ordre visuel voulu — même principe que ci-dessus mais avec
    // inversion, car ici la spec demande explicitement un rendu droite-à-gauche.
    val SPACE_LABEL: String = listOf("U+10FF9C", "U+10FFCC", "U+10FF8D", "U+10FF9D", "U+10FF9C", "U+10FF9A")
        .map { puaToString(it) }.reversed().joinToString("")
}

object KeyboardConfigLoader {

    fun load(context: Context, assetName: String = "ayneha_keyboard_config.json"): KeyboardConfig {
        val jsonText = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(jsonText)
        val layoutViewsJson = root.getJSONObject("layout_views")
        val views = LinkedHashMap<String, LayoutView>()
        for (viewId in layoutViewsJson.keys()) {
            val viewJson = layoutViewsJson.getJSONObject(viewId)
            views[viewId] = parseView(viewId, viewJson)
        }
        return KeyboardConfig(views)
    }

    private fun parseView(id: String, viewJson: JSONObject): LayoutView {
        val title = viewJson.optString("title", id)
        val rowsJson = viewJson.getJSONArray("rows")
        val allRows = ArrayList<RowDef>()
        for (i in 0 until rowsJson.length()) {
            val rowJson = rowsJson.getJSONObject(i)
            allRows.add(parseRow(rowJson))
        }
        // The row holding the SPACE key is the persistent bottom bar (switch/virgule/emoji/
        // espace/point/entrée) — pull it out so it can be pinned instead of scrolling with the
        // rest of the page's content (this matters most for "emoji", which has far more rows
        // than fit on screen).
        val bottomBarIndex = allRows.indexOfFirst { row -> row.keys.any { it.keyType == KeyType.SPACE } }
        val bottomBar = allRows.getOrNull(bottomBarIndex)
        val contentRows = if (bottomBarIndex >= 0) {
            allRows.filterIndexed { idx, _ -> idx != bottomBarIndex }
        } else {
            allRows
        }
        return LayoutView(id, title, contentRows, bottomBar)
    }

    private fun parseRow(rowJson: JSONObject): RowDef {
        val rowIndex = rowJson.optInt("row_index", 0)
        val mirror = rowJson.optBoolean("mirror", true)
        val keysJson = rowJson.getJSONArray("keys")
        val keys = ArrayList<KeyDef>()
        for (i in 0 until keysJson.length()) {
            keys.add(parseKey(keysJson.getJSONObject(i)))
        }
        return RowDef(rowIndex, keys, mirror)
    }

    private fun parseKey(keyJson: JSONObject): KeyDef {
        val typeStr = keyJson.optString("type", "char")
        val keyType = when (typeStr) {
            "action" -> KeyType.ACTION
            "switch_view" -> KeyType.SWITCH_VIEW
            "space" -> KeyType.SPACE
            else -> KeyType.CHAR
        }
        val label = keyJson.optString("label", "")
        val pua = if (keyJson.has("pua") && !keyJson.isNull("pua")) keyJson.getString("pua") else null
        val unicode = if (keyJson.has("unicode") && !keyJson.isNull("unicode")) keyJson.getString("unicode") else null
        val action = if (keyJson.has("action")) keyJson.getString("action") else null
        val targetView = if (keyJson.has("target_view")) keyJson.getString("target_view") else null
        val width = keyJson.optDouble("width", 1.0)
        val mirror = keyJson.optBoolean("mirror", false)

        val variants = ArrayList<KeyVariant>()
        val variantsJson: JSONArray? = keyJson.optJSONArray("long_press_variants")
        if (variantsJson != null) {
            for (i in 0 until variantsJson.length()) {
                val v = variantsJson.getJSONObject(i)
                val vLabel = v.optString("label", "")
                val vPua = if (v.has("pua") && !v.isNull("pua")) v.getString("pua") else null
                variants.add(KeyVariant(vLabel, vPua))
            }
        }

        return KeyDef(
            keyType = keyType,
            action = action,
            label = label,
            pua = pua,
            unicode = unicode,
            targetView = targetView,
            width = if (width <= 0) 1.0 else width,
            longPressVariants = variants,
            mirror = mirror
        )
    }
}
