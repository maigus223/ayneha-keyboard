// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 MAIGUS

package com.ayneha.keyboard

import android.os.Build

/**
 * Tracks which device manufacturers have agreed to (and shipped) AYNEHA as a native system
 * fallback font. Until a manufacturer is added here, AYNEHA text typed on their devices will
 * render as blank boxes ("tofu") for any recipient without the AYNEHA font/app installed —
 * see the outreach notes on Transsion/Huawei/Samsung for context.
 *
 * A simple manufacturer allow-list, checked with Typeface.hasGlyph()-style runtime probing,
 * is deliberately NOT used: a system font can report "having" a glyph at an AYNEHA PUA
 * codepoint without it being the correct AYNEHA glyph (PUA codepoints have no fixed meaning
 * and may collide with an unrelated icon/symbol font already using the same slot on that
 * device). A manual list Ma\u00efgus updates himself, one confirmed integration at a time, is the
 * only reliable signal here.
 *
 * HOW TO UPDATE: once a manufacturer has shipped the AYNEHA font as a system fallback font
 * (confirmed, not just "in discussion"), add their Build.MANUFACTURER value below, in
 * upper case. Common values: "XIAOMI", "SAMSUNG", "HUAWEI", "TRANSSION" (some Tecno/Infinix/
 * iTel devices report "TRANSSION", "TECNO", "INFINIX" or "ITEL" depending on model/build —
 * add whichever value actually shows up once a real device is tested).
 */
object DeviceFontSupport {

    /** Empty until a manufacturer integration is confirmed and shipped. */
    private val CONFIRMED_MANUFACTURERS: Set<String> = setOf(
        // "TECNO", "INFINIX", "ITEL", "TRANSSION", "HUAWEI", "SAMSUNG"
    )

    fun isNativelySupported(): Boolean {
        return Build.MANUFACTURER.uppercase() in CONFIRMED_MANUFACTURERS
    }
}
