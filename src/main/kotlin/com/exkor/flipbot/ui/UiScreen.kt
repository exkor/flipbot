package com.exkor.flipbot.ui

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.max
import kotlin.math.min

/**
 * Base for the vector-drawn screens: everything is drawn on the vector layer (rounded shapes, Inter text) in
 * immediate mode, with the clickable areas collected while drawing.
 */
abstract class UiScreen(title: String) : Screen(Component.literal(title)), HasUiOverlay {

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: () -> Unit) {
        fun contains(mx: Int, my: Int) = mx >= x && mx < x + w && my >= y && my < y + h
    }

    private class Drag(val x: Int, val y: Int, val w: Int, val h: Int, val onMove: (Int, Int) -> Unit, val onRelease: () -> Unit)

    private class Scroll(val x: Int, val y: Int, val w: Int, val h: Int, val onScroll: (Double) -> Unit)

    protected var px = 0; protected var py = 0; protected var pw = 0; protected var ph = 0
    protected var vw = 0; protected var vh = 0
    protected var mx = 0; protected var my = 0
    protected var sc = 1f
    private val hits = ArrayList<Hit>()
    private val drags = ArrayList<Drag>()
    private val scrolls = ArrayList<Scroll>()
    private var activeDrag: Drag? = null
    private val clips = ArrayList<IntArray>()
    protected var focused: EditBox? = null; private set
    private var tooltip: String? = null
    private val openedAt = System.currentTimeMillis()

    /** Panel size wanted, before fitting it to the window. */
    protected abstract fun panelSize(): Pair<Int, Int>
    protected abstract fun draw(ctx: GuiGraphicsExtractor)
    protected open fun onDone() = onClose()

    override fun init() {
        sc = UiScale.factor()
        vw = (width / sc).toInt(); vh = (height / sc).toInt()
        val (w, h) = panelSize()
        pw = min(w, vw - 16); ph = min(h, vh - 16)
        px = (vw - pw) / 2; py = max(8, (vh - ph) / 2)
    }

    override fun isPauseScreen(): Boolean = false
    override fun extractBackground(ctx: GuiGraphicsExtractor, mx: Int, my: Int, d: Float) {}
    override fun extractTransparentBackground(ctx: GuiGraphicsExtractor) {}
    override fun paintUiOverlay() = UiRenderer.paint(width, height, UiScale.factor())

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        sc = UiScale.factor()
        mx = UiScale.vx(mouseX); my = UiScale.vx(mouseY)
        UiRecorder.clear()
        hits.clear(); drags.clear(); scrolls.clear(); clips.clear(); tooltip = null
        // Fade the backdrop in over 150 ms.
        val fade = ((System.currentTimeMillis() - openedAt) / 150f).coerceIn(0f, 1f)
        Theme.rect(0, 0, vw + 1, vh + 1, Theme.withAlpha(0x05050C, (0xB4 * fade).toInt()))
        UiRecorder.dropShadow(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(), 12f, 24f, 0x90000000.toInt())
        Theme.ring(px, py, pw, ph, 12f, Theme.BACKGROUND, Theme.BORDER)
        runCatching { draw(ctx) }.onFailure { MenuDiag.fail("UiScreen.1", "${title.string} draw failed", it) }
        tooltip?.let { drawTooltip(it) }
        super.extractRenderState(ctx, mouseX, mouseY, delta)
    }

    // ---- drawing helpers ----

    protected fun text(s: String, x: Number, y: Number, size: Float, color: Int) = UiRecorder.text(s, x.toFloat(), y.toFloat(), size, color)
    protected fun bold(s: String, x: Number, y: Number, size: Float, color: Int) = UiRecorder.textBold(s, x.toFloat(), y.toFloat(), size, color)
    protected fun tw(s: String, size: Float): Float = UiRecorder.textWidth(s, size)
    protected fun twb(s: String, size: Float): Float = UiRecorder.textWidthBold(s, size)

    /** Text cut with an ellipsis to fit [maxW]. */
    protected fun fit(s: String, size: Float, maxW: Float): String {
        if (tw(s, size) <= maxW) return s
        var n = s.length
        while (n > 1 && tw(s.substring(0, n) + "…", size) > maxW) n--
        return s.substring(0, n) + "…"
    }

    protected fun tip(s: String) { tooltip = s }

    private fun drawTooltip(s: String) {
        val size = 7f
        val w = tw(s, size) + 12f
        val h = 16f
        var tx = mx + 8f
        if (tx + w > vw - 4) tx = mx - 8f - w
        val ty = (my - 20f).coerceAtLeast(4f)
        UiRecorder.dropShadow(tx, ty, w, h, 6f, 8f, 0x80000000.toInt())
        Theme.ring(tx, ty, w, h, 6f, Theme.CARD, Theme.BORDER)
        text(s, tx + 6f, ty + (h - size) / 2f, size, Theme.TEXT)
    }

    private fun clipped(x: Int, y: Int, w: Int, h: Int): IntArray? {
        var x0 = x; var y0 = y; var x1 = x + w; var y1 = y + h
        clips.lastOrNull()?.let { c -> x0 = max(x0, c[0]); y0 = max(y0, c[1]); x1 = min(x1, c[0] + c[2]); y1 = min(y1, c[1] + c[3]) }
        return if (x1 <= x0 || y1 <= y0) null else intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    protected fun inside(x: Int, y: Int, w: Int, h: Int): Boolean {
        val c = clips.lastOrNull()
        if (c != null && (mx < c[0] || my < c[1] || mx >= c[0] + c[2] || my >= c[1] + c[3])) return false
        return mx >= x && mx < x + w && my >= y && my < y + h
    }

    protected fun hit(x: Int, y: Int, w: Int, h: Int, action: () -> Unit) {
        val r = clipped(x, y, w, h) ?: return
        hits.add(Hit(r[0], r[1], r[2], r[3], action))
    }

    protected fun drag(x: Int, y: Int, w: Int, h: Int, onRelease: () -> Unit = {}, onMove: (Int, Int) -> Unit) {
        val r = clipped(x, y, w, h) ?: return
        drags.add(Drag(r[0], r[1], r[2], r[3], onMove, onRelease))
    }

    /** Wheel scrolling over an area; [onScroll] gets the wheel amount (positive = up). */
    protected fun scrollArea(x: Int, y: Int, w: Int, h: Int, onScroll: (Double) -> Unit) {
        scrolls.add(Scroll(x, y, w, h, onScroll))
    }

    protected fun pushClip(x: Int, y: Int, w: Int, h: Int) {
        clips.add(intArrayOf(x, y, w, h))
        UiRecorder.pushScissor(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
    }

    protected fun popClip() {
        clips.removeLastOrNull()
        UiRecorder.popScissor()
    }

    // ---- widgets ----

    protected fun field(v: String = "", maxLength: Int = 32, onChange: (String) -> Unit = {}) =
        EditBox(font, 0, 0, 100, 18, Component.literal("")).also {
            it.setMaxLength(maxLength); it.setBordered(false); it.setValue(v); it.setResponder(onChange)
        }

    /** Sets a field without telling its listener. */
    protected fun setQuietly(f: EditBox, v: String, listener: (String) -> Unit) {
        f.setResponder {}; f.value = v; f.setResponder(listener)
    }

    protected fun setFocus(f: EditBox?) {
        focused?.isFocused = false
        focused = f
        f?.isFocused = true
    }

    protected fun drawField(f: EditBox, x: Int, y: Int, w: Int, h: Int, placeholder: String, bad: Boolean = false) {
        val foc = focused === f
        val border = when { bad -> Theme.DANGER; foc -> Theme.PRIMARY; inside(x, y, w, h) -> Theme.ACCENT; else -> Theme.BORDER }
        if (foc) Theme.round(x - 2, y - 2, w + 4, h + 4, 8f, Theme.PRIMARY_DIM)
        Theme.ring(x, y, w, h, 6f, Theme.MUTED, border)
        if (f.value.isEmpty() && !foc) text(placeholder, x + 5f, y + (h - 7.5f) / 2f, 7.5f, Theme.DIM)
        else Theme.fieldContent(f, foc, x, y, w, h, 7.5f)
        hit(x, y, w, h) { setFocus(f) }
    }

    enum class Style { PRIMARY, OUTLINE, DANGER, GHOST }

    protected fun button(label: String, x: Int, y: Int, w: Int, h: Int = 20, style: Style = Style.OUTLINE, enabled: Boolean = true, action: () -> Unit) {
        val hov = enabled && inside(x, y, w, h)
        val size = 7.5f
        var textColor = if (enabled) Theme.TEXT else Theme.DIM
        when (style) {
            Style.PRIMARY -> {
                if (hov) Theme.round(x - 2, y - 2, w + 4, h + 4, 8f, Theme.PRIMARY_DIM)
                Theme.round(x, y, w, h, 6f, if (!enabled) Theme.MUTED else if (hov) Theme.PRIMARY_HOVER else Theme.PRIMARY)
                if (enabled) textColor = Theme.ON_PRIMARY
            }
            Style.DANGER -> {
                Theme.ring(x, y, w, h, 6f, if (hov) Theme.withAlpha(Theme.DANGER, 0x33) else Theme.CARD, if (enabled) Theme.DANGER else Theme.BORDER)
                if (enabled) textColor = Theme.DANGER
            }
            Style.OUTLINE -> Theme.ring(x, y, w, h, 6f, if (hov) Theme.ACCENT else Theme.CARD, if (hov) Theme.PRIMARY else Theme.BORDER)
            Style.GHOST -> if (hov) Theme.round(x, y, w, h, 6f, Theme.ACCENT)
        }
        val lw = if (style == Style.PRIMARY) twb(label, size) else tw(label, size)
        val tx = x + (w - lw) / 2f
        val ty = y + (h - size) / 2f
        if (style == Style.PRIMARY) bold(label, tx, ty, size, textColor) else text(label, tx, ty, size, textColor)
        if (enabled) hit(x, y, w, h, action)
    }

    /** A pill switch, 22x12. */
    protected fun toggle(on: Boolean, x: Int, y: Int, enabled: Boolean = true, flip: () -> Unit) {
        val hov = enabled && inside(x - 2, y - 2, 26, 16)
        Theme.ring(x, y, 22, 12, 6f, if (on) Theme.PRIMARY else Theme.MUTED, if (on) Theme.PRIMARY else if (hov) Theme.PRIMARY else Theme.BORDER)
        UiRecorder.disc(if (on) x + 16f else x + 6f, y + 6f, 4f, if (on) Theme.ON_PRIMARY else if (enabled) Theme.SUB else Theme.DIM)
        if (enabled) hit(x - 2, y - 2, 26, 16, flip)
    }

    /** A track slider over [lo, hi] snapped to [step]. */
    protected fun slider(value: Double, lo: Double, hi: Double, step: Double, x: Int, y: Int, w: Int, onChange: (Double) -> Unit) {
        val f = ((value - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()
        val hov = inside(x - 4, y - 5, w + 8, 14)
        Theme.round(x, y + 1, w, 4, 2f, Theme.MUTED)
        Theme.round(x, y + 1, w * f, 4, 2f, Theme.PRIMARY)
        if (hov) UiRecorder.disc(x + w * f, y + 3f, 7.5f, Theme.PRIMARY_DIM)
        UiRecorder.disc(x + w * f, y + 3f, 5f, Theme.PRIMARY)
        UiRecorder.disc(x + w * f, y + 3f, 2.5f, Theme.BACKGROUND)
        drag(x - 4, y - 5, w + 8, 14) { dx, _ ->
            val raw = lo + ((dx - x).toDouble() / w).coerceIn(0.0, 1.0) * (hi - lo)
            onChange((Math.round(raw / step) * step).coerceIn(lo, hi))
        }
    }

    /** A horizontal progress bar; [f] in 0..1. */
    protected fun progress(f: Double, x: Int, y: Int, w: Int, h: Int = 5, color: Int = Theme.PRIMARY) {
        Theme.round(x, y, w, h, h / 2f, Theme.MUTED)
        val fw = (w * f.coerceIn(0.0, 1.0)).toFloat()
        if (fw > 0.5f) Theme.round(x, y, fw.coerceAtLeast(h.toFloat()), h, h / 2f, color)
    }

    /** A small rounded label; returns its width. */
    protected fun badge(label: String, x: Int, y: Int, color: Int, filled: Boolean = false): Int {
        val size = 6.5f
        val w = (twb(label, size) + 10).toInt()
        if (filled) Theme.round(x, y, w, 13, 6.5f, color)
        else Theme.ring(x, y, w, 13, 6.5f, Theme.withAlpha(color, 0x26), Theme.withAlpha(color, 0x99))
        bold(label, x + 5, y + 3.25f, size, if (filled) Theme.ON_PRIMARY else color)
        return w
    }

    // ---- input ----

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val x = UiScale.vx(click.x()); val y = UiScale.vx(click.y())
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(click, doubled)
        drags.lastOrNull { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h }?.let {
            setFocus(null)
            activeDrag = it
            it.onMove(x, y)
            return true
        }
        val h = hits.asReversed().firstOrNull { it.contains(x, y) }
        setFocus(null)
        if (h == null) return super.mouseClicked(click, doubled)
        h.action()
        return true
    }

    override fun mouseDragged(click: MouseButtonEvent, deltaX: Double, deltaY: Double): Boolean {
        val d = activeDrag ?: return super.mouseDragged(click, deltaX, deltaY)
        d.onMove(UiScale.vx(click.x()), UiScale.vx(click.y()))
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        val d = activeDrag ?: return super.mouseReleased(click)
        activeDrag = null
        d.onRelease()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val x = UiScale.vx(mouseX); val y = UiScale.vx(mouseY)
        val s = scrolls.lastOrNull { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h }
            ?: return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        s.onScroll(scrollY)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        val f = focused
        if (f != null) {
            val k = input.key()
            if (k == InputConstants.KEY_ESCAPE || k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) { setFocus(null); return true }
            f.keyPressed(input)
            return true
        }
        if (input.key() == InputConstants.KEY_ESCAPE) { onDone(); return true }
        return super.keyPressed(input)
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        val f = focused ?: return super.charTyped(input)
        f.charTyped(input)
        return true
    }
}
