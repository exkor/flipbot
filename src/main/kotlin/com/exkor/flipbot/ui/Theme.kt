package com.exkor.flipbot.ui

import net.minecraft.client.gui.components.EditBox

/**
 * Colours: a deep-navy dark theme with lavender accents. ARGB ints.
 */
object Theme {
    val BACKGROUND = 0xFF0F0F1A.toInt()
    val CARD = 0xFF1A1A2E.toInt()
    val MUTED = 0xFF222244.toInt()
    val ACCENT = 0xFF303060.toInt()
    val SECONDARY = 0xFF2D2B55.toInt()
    val BORDER = 0xFF303052.toInt()
    val PRIMARY = 0xFFA48FFF.toInt()
    val PRIMARY_HOVER = 0xFFB8A7FF.toInt()
    val PRIMARY_DIM = 0x33A48FFF
    val ON_PRIMARY = 0xFF0F0F1A.toInt()
    val TEXT = 0xFFE2E2F5.toInt()
    val TEXT_SOFT = 0xFFC4C2FF.toInt()
    val SUB = 0xFFA0A0C0.toInt()
    val DIM = 0xFF6A6A8E.toInt()
    val DANGER = 0xFFFF5470.toInt()
    val GOOD = 0xFF6EE7B7.toInt()
    const val RADIUS = 8f

    fun withAlpha(color: Int, alpha: Int) = (alpha.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

    fun rect(x: Number, y: Number, w: Number, h: Number, color: Int) =
        UiRecorder.fillRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), color)

    fun round(x: Number, y: Number, w: Number, h: Number, r: Float, color: Int) =
        UiRecorder.fillRoundedRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), r, color)

    fun ring(x: Number, y: Number, w: Number, h: Number, r: Float, fill: Int, border: Int, stroke: Float = 1f) =
        UiRecorder.roundedRectRing(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), r, stroke, fill, border)

    /** A card: CARD fill, thin BORDER ring, 8px corners. */
    fun card(x: Number, y: Number, w: Number, h: Number, fill: Int = CARD, border: Int = BORDER) =
        ring(x, y, w, h, RADIUS, fill, border)

    /** Text of an EditBox with cursor and horizontal scroll. */
    fun fieldContent(field: EditBox, focused: Boolean, x: Int, y: Int, w: Int, h: Int, size: Float) {
        val text = field.value
        val cursor = field.cursorPosition.coerceIn(0, text.length)
        val cursorX = UiRecorder.textWidth(text.substring(0, cursor), size)
        val pad = 5f
        val visibleW = w - pad * 2f
        val scroll = if (focused) Math.max(0f, cursorX - visibleW) else 0f
        UiRecorder.pushScissor((x + 1).toFloat(), (y + 1).toFloat(), (w - 2).toFloat(), (h - 2).toFloat())
        try {
            UiRecorder.text(text, x + pad - scroll, y + (h - size) / 2f, size, TEXT)
            if (focused && (System.currentTimeMillis() / 500) % 2 == 0L) {
                UiRecorder.fillRect(x + pad + cursorX - scroll, (y + 3).toFloat(), 1f, (h - 6).toFloat(), PRIMARY)
            }
        } finally {
            UiRecorder.popScissor()
        }
    }
}
