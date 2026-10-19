// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 MAIGUS

package com.ayneha.keyboard

interface KeyboardActionListener {
    /** Insert literal text (a base character or a chosen long-press variant). */
    fun onText(text: String)

    /** Non-text action: "backspace", "enter". */
    fun onAction(action: String)

    /** Switch the visible layout view: "alphabet", "symbols_page1", "numpad", "emoji". */
    fun onSwitchView(targetView: String)
}
