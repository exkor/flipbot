package com.exkor.flipbot.ui

import com.exkor.flipbot.craft.CollectionScanner
import com.exkor.flipbot.craft.Collections
import com.exkor.flipbot.craft.CraftFlipper
import com.exkor.flipbot.flipper.Bankroll
import com.exkor.flipbot.flipper.data.ItemNames
import com.exkor.flipbot.flipper.FlipperConfig
import com.exkor.flipbot.flipper.FlipperMod
import com.exkor.flipbot.flipper.analysis.BazaarFlipTracker
import com.exkor.flipbot.flipper.practice.PracticeBook
import com.exkor.flipbot.flipper.trading.BazaarDailyLimit
import com.exkor.flipbot.flipper.trading.ScreenUtil
import com.exkor.flipbot.flipper.trading.Trader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import java.util.Locale
import kotlin.math.max

/**
 * The FlipBot menu: one compact floating window, tabs across the top (Overview, Orders, Flips, settings), big
 * numbers, almost no boxes. Deep navy / lavender palette. Opened with /flipbot or the menu key.
 */
class FlipBotScreen : UiScreen("FlipBot") {

    companion object {
        private var lastPage = Page.OVERVIEW

        /** Opens the menu on the next tick (a command's chat screen closes after the command runs). */
        @JvmStatic
        fun open() {
            val mc = Minecraft.getInstance()
            mc.schedule { mc.gui.setScreen(FlipBotScreen()) }
        }

        private const val PAD = 18
        private const val BAR_H = 34
    }

    enum class Page(val title: String) { OVERVIEW("Overview"), ORDERS("Orders"), FLIPS("Flips"), CRAFTS("Crafts"), SETTINGS("Settings") }

    private val cfg: FlipperConfig get() = FlipperMod.CONFIG
    private var page = lastPage
    private var scroll = 0f
    private var scrollTarget = 0f
    private var notice: String? = null
    private var noticeBad = false
    private var noticeUntil = 0L
    private var confirmUntil = 0L
    private var confirmAction: String? = null
    private var trackerStarted = false
    private var dirty = false
    private var showAdvanced = false

    override fun panelSize(): Pair<Int, Int> = 460 to 300

    override fun init() {
        UiScale.user = cfg.uiScale.toFloat().coerceIn(0.6f, 1.5f)
        Easing.animations = cfg.uiAnimations
        super.init()
        if (page == Page.FLIPS) startTracker()
        if (page == Page.CRAFTS && CraftFlipper.plans().isEmpty()) CraftFlipper.refresh()
    }

    override fun removed() {
        lastPage = page
        if (trackerStarted) BazaarFlipTracker.stop()
        trackerStarted = false
        if (dirty) cfg.save()
        super.removed()
    }

    private fun startTracker() {
        if (!trackerStarted) {
            BazaarFlipTracker.start()
            trackerStarted = true
        }
    }

    /** Switch page from outside (the dev self-test uses it for screenshots). */
    fun showPage(p: Page) = go(p)

    val currentPage: Page get() = page

    private fun go(p: Page) {
        if (p == page) return
        page = p
        scroll = 0f; scrollTarget = 0f
        setFocus(null)
        if (p == Page.FLIPS) startTracker()
        if (p == Page.CRAFTS && CraftFlipper.plans().isEmpty()) CraftFlipper.refresh()
    }

    private fun say(msg: String, bad: Boolean = false) {
        notice = msg; noticeBad = bad; noticeUntil = System.currentTimeMillis() + 3500
    }

    private fun live() = !cfg.practiceMode

    // ------------------------------------------------------------------ frame

    override fun draw(ctx: GuiGraphicsExtractor) {
        drawTopBar()
        val x = px + PAD
        val w = pw - PAD * 2
        val top = py + BAR_H + 14
        val bottom = py + ph - 10
        scroll += (scrollTarget - scroll) * 0.35f
        pushClip(x - 6, top - 4, w + 12, bottom - top + 8)
        val y0 = top - scroll.toInt()
        val used = when (page) {
            Page.OVERVIEW -> drawOverview(x, y0, w)
            Page.ORDERS -> drawOrders(x, y0, w)
            Page.FLIPS -> drawFlips(x, y0, w)
            Page.CRAFTS -> drawCrafts(x, y0, w)
            Page.SETTINGS -> drawSettings(x, y0, w)
        }
        popClip()
        val view = bottom - top
        val maxScroll = max(0, used - view).toFloat()
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        scrollArea(x - 6, top, w + 12, view) { amount -> scrollTarget = (scrollTarget - amount.toFloat() * 26f).coerceIn(0f, maxScroll) }
        if (maxScroll > 0) {
            val barH = max(20f, view * view / used.toFloat())
            val barY = top + (view - barH) * (scroll / maxScroll)
            Theme.round(px + pw - 7, barY, 2, barH, 1f, Theme.BORDER)
        }
        drawNotice()
    }

    /** Brand, tabs and state in one bar; the active tab gets a lavender underline. */
    private fun drawTopBar() {
        val y = py
        bold("FlipBot", px + PAD, y + 13, 9f, Theme.TEXT)
        var tx = px + PAD + twb("FlipBot", 9f).toInt() + 22
        for (p in Page.entries) {
            val active = p == page
            if (p == Page.SETTINGS) {
                // The gear sits at the right end, before the state dot.
                continue
            }
            val tw = tw(p.title, 7.5f).toInt()
            val hov = inside(tx - 6, y + 6, tw + 12, BAR_H - 10)
            text(p.title, tx, y + 14, 7.5f, if (active) Theme.TEXT else if (hov) Theme.TEXT_SOFT else Theme.SUB)
            if (active) Theme.round(tx, y + BAR_H - 3, tw, 2, 1f, Theme.PRIMARY)
            hit(tx - 6, y + 6, tw + 12, BAR_H - 10) { go(p) }
            tx += tw + 18
        }
        // State dot + gear on the right
        val running = FlipperMod.isRunning()
        val paused = running && Trader.get().isPaused
        val dotColor = when { paused -> Theme.TEXT_SOFT; running -> Theme.GOOD; else -> Theme.DIM }
        val gx = px + pw - PAD - 10
        val gActive = page == Page.SETTINGS
        val gHov = inside(gx - 5, y + 8, 20, 20)
        gear(gx, y + 13, if (gActive) Theme.PRIMARY else if (gHov) Theme.TEXT else Theme.SUB)
        if (gActive) Theme.round(gx - 1, y + BAR_H - 3, 12, 2, 1f, Theme.PRIMARY)
        hit(gx - 5, y + 8, 20, 20) { go(Page.SETTINGS) }
        if (gHov) tip("Settings")
        val dx = gx - 14f
        if (running && !paused) {
            val pulse = (System.currentTimeMillis() % 1600) / 1600f
            UiRecorder.disc(dx, y + 17.5f, 2.5f + 3f * pulse, Theme.withAlpha(dotColor, (0x66 * (1 - pulse)).toInt()))
        }
        UiRecorder.disc(dx, y + 17.5f, 2.5f, dotColor)
        if (inside(dx.toInt() - 5, y + 10, 10, 15)) tip(when { paused -> "Paused"; running -> "Running"; else -> "Stopped" })
        Theme.rect(px + 1, y + BAR_H, pw - 2, 1, Theme.withAlpha(Theme.BORDER, 0xAA))
    }

    private fun gear(x: Int, y: Int, c: Int) {
        UiRecorder.roundedRectRing(x + 1f, y + 1f, 8f, 8f, 4f, 1.6f, 0, c)
        UiRecorder.disc(x + 5f, y + 5f, 1.4f, c)
    }

    private fun drawNotice() {
        val msg = notice ?: return
        if (System.currentTimeMillis() > noticeUntil) { notice = null; return }
        val nw = (tw(msg, 7f) + 20).toInt()
        val nx = px + (pw - nw) / 2
        val ny = py + ph - 30
        UiRecorder.dropShadow(nx.toFloat(), ny.toFloat(), nw.toFloat(), 20f, 8f, 10f, 0x80000000.toInt())
        Theme.round(nx, ny, nw, 20, 10f, Theme.ACCENT)
        text(msg, nx + 10, ny + 6.5f, 7f, if (noticeBad) Theme.DANGER else Theme.TEXT)
    }

    private fun label(s: String, x: Number, y: Number) = text(s.uppercase(Locale.ROOT), x, y, 5.8f, Theme.DIM)

    // ------------------------------------------------------------------ overview

    private fun drawOverview(x: Int, y0: Int, w: Int): Int {
        var y = y0
        val running = FlipperMod.isRunning()
        val paused = running && Trader.get().isPaused

        // Status line + the one main action
        val state = when { paused -> "Paused"; running -> "Running"; else -> "Stopped" }
        bold(state, x, y + 2, 10f, Theme.TEXT)
        val mode = if (live()) "Live" else "Practice"
        val session = FlipperMod.sessionStartedAt()
        val sub = mode + (if (running && session > 0) " · ${duration(System.currentTimeMillis() - session)}" else "")
        text(sub, x, y + 17, 7f, if (live()) Theme.DANGER else Theme.SUB)
        val bw = 70
        val bx = x + w - bw
        if (running) {
            button("Stop", bx, y + 2, bw, 22, Style.DANGER) { FlipperMod.stop(); say("Stopping…") }
            button(if (paused) "Resume" else "Pause", bx - bw - 6, y + 2, bw, 22, Style.GHOST) { FlipperMod.setPaused(!paused) }
        } else {
            val onSb = runCatching { ScreenUtil.onSkyBlock() }.getOrDefault(false)
            button("Start", bx, y + 2, bw, 22, Style.PRIMARY, enabled = onSb) {
                FlipperMod.start(); say(if (live()) "Started live" else "Started in practice")
            }
            if (!onSb && inside(bx, y + 2, bw, 22)) tip("Join Hypixel SkyBlock to start")
        }
        y += 44

        // Three big numbers
        val col = w / 3
        val report = FlipperMod.practice().report()
        val ledger = FlipperMod.ledger()
        val profit = if (live()) FlipperMod.profitMade() else report.realizedProfit()
        val perHour = if (live()) FlipperMod.liveProfitPerHour() else if (report.hours() > 0.05) report.realizedPerHour() else -1.0
        val open = if (live()) ledger.openOrderCount() else report.open()
        big(x, y, coins(profit, signed = true), if (profit > 0) Theme.GOOD else if (profit < 0) Theme.DANGER else Theme.TEXT,
            "profit", if (live()) "${ledger.tradesCompleted()} flips, real cost only" else "${report.completedSells()} practice flips")
        big(x + col, y, if (perHour >= 0) coins(perHour, signed = true) else "—", Theme.TEXT,
            "per hour", if (live()) "this session" else "practice")
        big(x + col * 2, y, "$open / ${cfg.maxOrders}", Theme.TEXT, "orders", "open now")
        y += 50

        Theme.rect(x, y, w, 1, Theme.withAlpha(Theme.BORDER, 0x80))
        y += 14

        // Budget (profit only) or bankroll, and the daily limit
        val half = (w - 24) / 2
        val purse = ScreenUtil.lastKnownPurse()
        if (cfg.tradeWithProfitOnly) {
            val budget = max(0.0, FlipperMod.profitMade())
            val inOrders = report.invested().takeIf { !live() } ?: ledgerInOrders()
            meter(x, y, half, "Budget", if (budget > 0) "${coins(inOrders)} in orders · ${coins(budget)}" else "no profit yet",
                if (budget > 0) inOrders / budget else 0.0, Theme.PRIMARY,
                "Trade only with profit: the bot never has more in the Bazaar than the ${coins(budget)} it has made")
        } else {
            val bankroll = if (purse >= 0) Bankroll.of(cfg, purse, FlipperMod.profitMade()) else -1.0
            meter(x, y, half, "Bankroll", if (bankroll >= 0) coins(bankroll) else "purse not read yet",
                if (purse > 0 && bankroll >= 0) bankroll / purse else 0.0, Theme.PRIMARY,
                "Purse minus the ${coins(cfg.reserveCoins.toDouble())} reserve")
        }
        val limit = BazaarDailyLimit.LIMIT_COINS.toDouble()
        val buy = BazaarDailyLimit.buyCoins().toDouble()
        val reset = BazaarDailyLimit.resetAt() - System.currentTimeMillis()
        meter(x + half + 24, y, half, "Daily buy limit", "${coins(buy)} of ${coins(limit)}", buy / limit,
            if (buy >= limit) Theme.DANGER else Theme.PRIMARY,
            if (reset > 0) "Hypixel's 10B daily limit · resets in ${duration(reset)}" else "Hypixel's 10B daily limit · resets at 00:00 UTC")
        y += 34

        // Which rules are on, as quiet chips
        var cx = x
        if (cfg.tradeWithProfitOnly) cx += ruleChip("Profit only", cx, y, "Trades only with profit already made") + 6
        if (cfg.onlySellAtProfit) cx += ruleChip("No loss sells", cx, y, "Never sells below cost plus tax") + 6
        if (cfg.finishBatchFirst) cx += ruleChip("Batch by batch", cx, y, "Buys again only after everything is sold") + 6
        y += 30

        // The current orders, short
        label("Open orders", x, y)
        val all = "All orders"
        val aw = tw(all, 6.5f)
        val ah = inside((x + w - aw).toInt() - 4, y - 4, aw.toInt() + 8, 14)
        text(all, x + w - aw, y - 0.5f, 6.5f, if (ah) Theme.TEXT else Theme.PRIMARY)
        hit((x + w - aw).toInt() - 4, y - 4, aw.toInt() + 8, 14) { go(Page.ORDERS) }
        y += 12
        val cols = intArrayOf(x, x + (w * 0.42).toInt(), x + (w * 0.53).toInt(), x + (w * 0.68).toInt(), x + (w * 0.88).toInt())
        var shown = 0
        if (live()) {
            for (o in Trader.ordersView().take(4)) {
                row(cols, y, w, o.itemName().trim(), o.sellSide(), o.unitPrice(),
                    if (o.amount() > 0) o.filled().toDouble() / o.amount() else 0.0, "${o.filled()}/${o.amount()}", Theme.SUB, true)
                y += 21; shown++
            }
        } else {
            for (o in FlipperMod.practice().orders.filter { it.state == PracticeBook.State.OPEN }.take(4)) {
                row(cols, y, w, (o.name ?: o.productId).trim(), o.side == PracticeBook.Side.SELL, o.price,
                    o.filled / max(1, o.qty.toInt()), "${Math.floor(o.filled + 1e-9).toLong()}/${o.qty}", Theme.SUB, true)
                y += 21; shown++
            }
        }
        if (shown == 0) {
            text("None right now.", x, y + 4, 7f, Theme.DIM)
            y += 18
        }
        return y - y0
    }

    private fun big(x: Int, y: Int, value: String, color: Int, title: String, hint: String) {
        bold(value, x, y, 15f, color)
        label(title, x, y + 22)
        if (inside(x, y - 2, 120, 32)) tip(hint)
    }

    private fun meter(x: Int, y: Int, w: Int, title: String, value: String, f: Double, color: Int, hint: String) {
        label(title, x, y)
        text(value, x + w - tw(value, 6.5f), y - 0.5f, 6.5f, Theme.SUB)
        progress(f, x, y + 12, w, 4, color)
        if (inside(x, y - 4, w, 22)) tip(hint)
    }

    private fun ruleChip(label: String, x: Int, y: Int, hint: String): Int {
        val w = (tw(label, 6.5f) + 14).toInt()
        Theme.round(x, y, w, 14, 7f, Theme.withAlpha(Theme.PRIMARY, 0x1F))
        text(label, x + 7, y + 3.8f, 6.5f, Theme.TEXT_SOFT)
        if (inside(x, y, w, 14)) tip(hint)
        return w
    }

    /** Coins the live bot has in the Bazaar: unsold items it bought plus open buy orders, from the ledger. */
    private fun ledgerInOrders(): Double {
        var sum = 0.0
        for (t in FlipperMod.ledger().all()) {
            if (t.isOpen) sum += max(t.cost, t.qty * t.buyPrice)
        }
        return sum
    }

    // ------------------------------------------------------------------ orders

    private fun drawOrders(x: Int, y0: Int, w: Int): Int {
        var y = y0
        val cols = intArrayOf(x, x + (w * 0.42).toInt(), x + (w * 0.53).toInt(), x + (w * 0.68).toInt(), x + (w * 0.88).toInt())
        label("Item", cols[0], y); label("Side", cols[1], y); label("Price", cols[2], y); label("Filled", cols[3], y)
        if (!live()) {
            val armed = confirmAction == "reset" && System.currentTimeMillis() < confirmUntil
            val l = if (armed) "Click again" else "Clear"
            val lw = (tw(l, 6.5f) + 12).toInt()
            button(l, x + w - lw, y - 4, lw, 14, if (armed) Style.DANGER else Style.GHOST) {
                if (armed) {
                    if (FlipperMod.isRunning()) say("Stop the bot first", true)
                    else { FlipperMod.resetPractice(); say("Practice cleared"); confirmAction = null }
                } else { confirmAction = "reset"; confirmUntil = System.currentTimeMillis() + 3000 }
            }
        }
        y += 12

        if (live()) {
            val orders = Trader.ordersView()
            if (orders.isEmpty()) {
                text("No orders yet. They show up after the bot's first scan of Manage Orders.", x, y + 8, 7f, Theme.SUB)
                return y + 26 - y0
            }
            for (o in orders) {
                row(cols, y, w, o.itemName().trim(), o.sellSide(), o.unitPrice(),
                    if (o.amount() > 0) o.filled().toDouble() / o.amount() else 0.0, "${o.filled()}/${o.amount()}", Theme.SUB, true)
                y += 21
            }
        } else {
            val book = FlipperMod.practice()
            val list = book.orders.filter { it.state == PracticeBook.State.OPEN } +
                book.orders.filter { it.state != PracticeBook.State.OPEN }.takeLast(25).reversed()
            if (list.isEmpty()) {
                text("No practice orders yet.", x, y + 8, 7f, Theme.SUB)
                return y + 26 - y0
            }
            for (o in list) {
                val open = o.state == PracticeBook.State.OPEN
                val (status, sc) = when (o.state) {
                    PracticeBook.State.OPEN -> (if (o.outbidTicks > 0) "outbid" else "${Math.floor(o.filled + 1e-9).toLong()}/${o.qty}") to
                        (if (o.outbidTicks > 0) Theme.DANGER else Theme.SUB)
                    PracticeBook.State.DONE -> if (o.side == PracticeBook.Side.SELL) coins(o.proceeds - o.cost, signed = true) to
                        (if (o.proceeds >= o.cost) Theme.GOOD else Theme.DANGER) else "filled" to Theme.DIM
                    else -> "cancelled" to Theme.DIM
                }
                row(cols, y, w, (o.name ?: o.productId).trim(), o.side == PracticeBook.Side.SELL, o.price,
                    o.filled / max(1, o.qty.toInt()), status, sc, open)
                y += 21
            }
        }
        return y - y0
    }

    private fun row(cols: IntArray, y: Int, w: Int, name: String, sell: Boolean, price: Double, fill: Double,
                    status: String, statusColor: Int, open: Boolean) {
        val x = cols[0]
        if (inside(x - 4, y, w + 8, 20)) Theme.round(x - 4, y, w + 8, 20, 5f, Theme.MUTED)
        text(fit(name, 7.3f, (cols[1] - cols[0] - 8).toFloat()), x, y + 6.5f, 7.3f, if (open) Theme.TEXT else Theme.SUB)
        bold(if (sell) "SELL" else "BUY", cols[1], y + 7, 6.3f, if (sell) Theme.GOOD else Theme.PRIMARY)
        text(price(price), cols[2], y + 6.5f, 7f, if (open) Theme.TEXT else Theme.SUB)
        progress(fill, cols[3], y + 9, cols[4] - cols[3] - 12, 3, if (sell) Theme.GOOD else Theme.PRIMARY)
        text(status, cols[4], y + 6.5f, 6.8f, statusColor)
    }

    // ------------------------------------------------------------------ flips

    private fun drawFlips(x: Int, y0: Int, w: Int): Int {
        var y = y0
        val snap = BazaarFlipTracker.snapshot()
        val scanning = BazaarFlipTracker.scanning()
        var cx = x
        for (s in BazaarFlipTracker.Sort.entries) {
            cx += chip(s.label, cx, y, BazaarFlipTracker.Sort.of(cfg.flipsSort) == s) {
                cfg.flipsSort = s.name; dirty = true; BazaarFlipTracker.resort()
            } + 5
        }
        val rw = 56
        button("Refresh", x + w - rw, y - 1, rw, 16, Style.GHOST, enabled = !scanning) { BazaarFlipTracker.requestRefresh() }
        if (scanning) text("Scanning…", x + w - rw - 8 - tw("Scanning…", 6.5f), y + 3.5f, 6.5f, Theme.DIM)
        y += 24

        if (snap.top.isEmpty()) {
            text(if (scanning) "Fetching prices…" else (snap.error ?: "Nothing passes the filters right now."), x, y + 4, 7f, Theme.SUB)
            return y + 24 - y0
        }
        for ((i, plan) in snap.top.withIndex()) {
            if (inside(x - 4, y, w + 8, 26)) {
                Theme.round(x - 4, y, w + 8, 26, 5f, Theme.MUTED)
                tip("${plan.qty}x · ${coins(plan.expectedProfit())} per flip")
            }
            text("${i + 1}", x, y + 9f, 6.8f, if (i < 3) Theme.PRIMARY else Theme.DIM)
            text(fit((plan.displayName ?: plan.productId).trim(), 7.3f, w * 0.45f), x + 14, y + 4, 7.3f, Theme.TEXT)
            text("${price(plan.buyPrice)} → ${price(plan.sellPrice)}", x + 14, y + 15f, 6.2f, Theme.DIM)
            val m = String.format(Locale.ROOT, "%.1f%%", plan.marginPercent)
            text(m, x + (w * 0.64).toInt(), y + 9f, 6.8f, Theme.TEXT_SOFT)
            val cph = coins(plan.coinsPerHour) + "/h"
            bold(cph, x + w - twb(cph, 7.3f), y + 8.5f, 7.3f, Theme.GOOD)
            y += 27
        }
        val bank = if (snap.bankrollKnown) "Planned with ${coins(snap.bankroll)}" else "Purse unknown, planned with 100M"
        text(bank + (if (snap.updatedAt > 0) " · ${ago(snap.updatedAt)} ago" else ""), x, y + 6, 6.2f, Theme.DIM)
        return y + 20 - y0
    }

    private fun chip(label: String, x: Int, y: Int, on: Boolean, action: () -> Unit): Int {
        val w = (tw(label, 6.8f) + 16).toInt()
        val hov = inside(x, y, w, 15)
        Theme.round(x, y, w, 15, 7.5f, if (on) Theme.PRIMARY else if (hov) Theme.ACCENT else Theme.CARD)
        text(label, x + 8, y + 4.2f, 6.8f, if (on) Theme.ON_PRIMARY else if (hov) Theme.TEXT else Theme.SUB)
        hit(x, y, w, 15, action)
        return w
    }

    // ------------------------------------------------------------------ crafts

    private fun drawCrafts(x: Int, y0: Int, w: Int): Int {
        var y = y0
        val scanned = Collections.scannedAt()
        val scanning = CollectionScanner.running()
        val busy = CraftFlipper.busy()

        // Status line + actions
        val info = when {
            scanning -> "Reading your collections…"
            scanned <= 0 -> "Collections not read yet: unlocks unknown"
            else -> "Collections read ${ago(scanned)} ago · level-ups update automatically"
        }
        text(fit(info, 6.5f, w - 150f), x, y + 3.5f, 6.5f, if (scanned <= 0 && !scanning) Theme.TEXT_SOFT else Theme.DIM)
        val rw = 56
        button("Refresh", x + w - rw, y - 1, rw, 16, Style.GHOST, enabled = !busy) { CraftFlipper.refresh() }
        val sl = if (scanned <= 0) "Read collections" else "Re-read"
        val sw = (tw(sl, 7f) + 16).toInt()
        val onSb = runCatching { ScreenUtil.onSkyBlock() }.getOrDefault(false)
        button(sl, x + w - rw - 6 - sw, y - 1, sw, 16, if (scanned <= 0) Style.PRIMARY else Style.GHOST, enabled = onSb && !busy) {
            val mc = Minecraft.getInstance()
            mc.gui.setScreen(null)
            CollectionScanner.scanAsync { mc.gui.setScreen(FlipBotScreen()) }
        }
        if (!onSb && inside(x + w - rw - 6 - sw, y - 1, sw, 16)) tip("Join SkyBlock to read your collections")
        y += 24

        val plans = CraftFlipper.plans()
        if (plans.isEmpty()) {
            text(CraftFlipper.status().ifEmpty { "Loading…" }, x, y + 4, 7f, Theme.SUB)
            return y + 24 - y0
        }
        label("Craft", x, y); label("Batch", x + (w * 0.56).toInt(), y); label("Profit", x + (w * 0.70).toInt(), y)
        y += 12
        for (p in plans.take(30)) {
            val r = p.recipe()
            val status = CraftFlipper.unlock(r)
            val hov = inside(x - 4, y, w + 8, 26)
            if (hov) Theme.round(x - 4, y, w + 8, 26, 5f, Theme.MUTED)
            val name = ItemNames.displayName(r.output()) ?: r.output()
            text(fit(name.trim() + if (r.outputCount() > 1) " ×${r.outputCount()}" else "", 7.3f, w * 0.52f), x, y + 4, 7.3f,
                if (status == Collections.Status.LOCKED) Theme.SUB else Theme.TEXT)
            val ingredients = r.ingredients().entries.joinToString(", ") { (id, n) -> "$n× ${(ItemNames.displayName(id) ?: id).trim()}" }
            text(fit(ingredients, 6.2f, w * 0.52f), x, y + 15, 6.2f, Theme.DIM)
            text("×${p.batchCrafts()}", x + (w * 0.56).toInt(), y + 9f, 6.8f, Theme.SUB)
            bold(if (p.batchCrafts() > 0) "+" + coins(p.batchProfit()) else "+" + coins(p.profitPerCraft()) + " ea", x + (w * 0.70).toInt(), y + 4, 7.3f, Theme.GOOD)
            text(String.format(Locale.ROOT, "%.1f%%", p.marginPercent()), x + (w * 0.70).toInt(), y + 15, 6.2f, Theme.DIM)
            val (chip, color) = when (status) {
                Collections.Status.UNLOCKED -> "unlocked" to Theme.GOOD
                Collections.Status.LOCKED -> "locked" to Theme.DANGER
                else -> "unclear" to Theme.DIM
            }
            val cw = (tw(chip, 6.3f) + 12).toInt()
            Theme.round(x + w - cw, y + 6, cw, 13, 6.5f, Theme.withAlpha(color, 0x26))
            text(chip, x + w - cw + 6, y + 9.3f, 6.3f, color)
            if (hov) tip(buildString {
                append(if (r.requirement().isBlank()) "No requirement listed (may be slayer-gated)" else r.requirement())
                append(" · per craft ${coins(p.costPerCraft())} → ${coins(p.revenuePerCraft())}")
                if (status == Collections.Status.UNKNOWN && r.requirement().isNotBlank()) append(" · not a collection, or not read yet")
                if (status == Collections.Status.UNKNOWN) append(" · never bought")
            })
            y += 27
        }
        text("Budget ${coins(CraftFlipper.budget())}" + (if (CraftFlipper.budget() <= 0) " (no profit made yet)" else "") + " · nothing is bought yet", x, y + 6, 6.2f, Theme.DIM)
        return y + 20 - y0
    }

    // ------------------------------------------------------------------ settings

    private sealed class Setting(val label: String, val help: String)
    private class Toggle(label: String, help: String, val get: () -> Boolean, val set: (Boolean) -> Unit,
                         val live: (() -> String?)? = null) : Setting(label, help)
    private class Range(label: String, help: String, val lo: Double, val hi: Double, val step: Double,
                        val get: () -> Double, val set: (Double) -> Unit, val fmt: (Double) -> String) : Setting(label, help)
    private class Num(label: String, help: String, val unit: String, val get: () -> Double, val set: (Double) -> Unit,
                      val min: Double = 0.0, val enabled: () -> Boolean = { true }) : Setting(label, help)
    private class Section(val title: String, val items: List<Setting>, val advanced: Boolean = false)

    private fun int(d: Double) = String.format(Locale.ROOT, "%.0f", d)
    private fun pct(d: Double) = String.format(Locale.ROOT, "%.1f%%", d)

    private val sections: List<Section> by lazy {
        listOf(
            Section("Mode", listOf(
                Toggle("Practice mode", "Walk the menus up to the confirm screen and simulate the order. Nothing is placed.",
                    { cfg.practiceMode }, { on -> setPractice(on, false) }),
            )),
            Section("Money", listOf(
                Toggle("Trade only with profit", "The bot never has more coins in the Bazaar than the profit it has made. Your own coins are never used.",
                    { cfg.tradeWithProfitOnly }, { cfg.tradeWithProfitOnly = it },
                    live = { coins(max(0.0, FlipperMod.profitMade())) + " to trade with" }),
                Toggle("Never sell at a loss", "Sell offers never go below what the item cost plus tax; when the market is lower, the offer waits.",
                    { cfg.onlySellAtProfit }, { cfg.onlySellAtProfit = it }),
                Toggle("Finish each batch first", "No new buy orders while bought items are still unsold: buy a batch, sell all of it, then the next.",
                    { cfg.finishBatchFirst }, { cfg.finishBatchFirst = it }),
                Num("Reserve", "Coins in the purse the bot never touches.", "M",
                    { cfg.reserveCoins / 1e6 }, { cfg.reserveCoins = (it * 1e6).toLong() }),
                Range("Max orders", "Open orders at once (Hypixel allows 14).", 1.0, 14.0, 1.0,
                    { cfg.maxOrders.toDouble() }, { cfg.maxOrders = it.toInt() }, ::int),
                Range("Min margin", "Profit after tax per coin spent, at least.", 0.0, 10.0, 0.1,
                    { cfg.minMarginPercent }, { cfg.minMarginPercent = it }, ::pct),
            )),
            Section("Without profit-only", listOf(
                Toggle("Use the whole purse", "When 'Trade only with profit' is off: bankroll = purse minus the reserve. Off: Max invested caps it.",
                    { cfg.investFromPurse }, { cfg.investFromPurse = it }),
                Num("Max invested", "Fixed cap on all orders together, when the whole purse is off. 0 = no cap.", "M",
                    { cfg.maxInvestedMillions }, { cfg.maxInvestedMillions = it }, enabled = { !cfg.investFromPurse && !cfg.tradeWithProfitOnly }),
            ), advanced = true),
            Section("Trading", listOf(
                Range("New orders per cycle", "Buy orders placed per cycle at most.", 1.0, 5.0, 1.0,
                    { cfg.maxNewOrdersPerCycle.toDouble() }, { cfg.maxNewOrdersPerCycle = it.toInt() }, ::int),
                Range("Cycle interval", "Seconds between trading cycles.", 5.0, 60.0, 1.0,
                    { cfg.cycleIntervalSeconds.toDouble() }, { cfg.cycleIntervalSeconds = it.toInt() }) { "${int(it)} s" },
                Range("Action delay", "Wait before each order action (default 1000 ms).", 0.0, 3000.0, 50.0,
                    { cfg.actionDelayMs.toDouble() }, { cfg.actionDelayMs = it.toLong() }) { "${int(it)} ms" },
                Range("Max share per item", "Of the bankroll, in one item.", 0.05, 1.0, 0.05,
                    { cfg.maxBankFractionPerItem }, { cfg.maxBankFractionPerItem = it }) { "${int(it * 100)}%" },
            ), advanced = true),
            Section("Filters", listOf(
                Range("Max margin", "Higher margins are usually fake or dead prices.", 5.0, 100.0, 1.0,
                    { cfg.maxMarginPercent }, { cfg.maxMarginPercent = it }, ::pct),
                Num("Min unit price", "Skip items cheaper than this per unit.", "k",
                    { cfg.minUnitCoins / 1e3 }, { cfg.minUnitCoins = it * 1e3 }),
                Num("Min weekly volume", "Units traded per week on the slower side.", "",
                    { cfg.minWeeklyVolume.toDouble() }, { cfg.minWeeklyVolume = it.toLong() }),
                Num("Min order size", "Each buy order is worth at least this much (less for small budgets).", "M",
                    { cfg.minOrderCoins / 1e6 }, { cfg.minOrderCoins = it * 1e6 }),
                Range("Max deviation from average", "Top-of-book price vs the weekly average.", 5.0, 100.0, 1.0,
                    { cfg.maxPriceDeviationPercent }, { cfg.maxPriceDeviationPercent = it }, ::pct),
                Toggle("Require price history", "Only trade items with Coflnet price history.",
                    { cfg.requireHistoricalPrice }, { cfg.requireHistoricalPrice = it }),
            ), advanced = true),
            Section("Interface", listOf(
                Range("Menu scale", "Size of this menu.", 0.7, 1.3, 0.05,
                    { cfg.uiScale }, { cfg.uiScale = it; UiScale.user = it.toFloat(); init() }) { "${int(it * 100)}%" },
                Toggle("Status HUD", "Mode, state and open orders in the corner while the bot runs.",
                    { cfg.hudEnabled }, { cfg.hudEnabled = it }),
            ), advanced = true),
        )
    }

    private val fields = HashMap<Num, EditBox>()

    private fun setPractice(on: Boolean, confirmed: Boolean) {
        val refused = FlipperMod.setPracticeMode(on, confirmed)
        if (refused == null) {
            say(if (on) "Practice mode on" else "Live: the next start places real orders", bad = !on)
            confirmAction = null
        } else if (!on && !FlipperMod.isRunning()) {
            confirmAction = "live"; confirmUntil = System.currentTimeMillis() + 6000
            say("No spending limit set. Click \"Go live\" to confirm", true)
        } else {
            say(refused, true)
        }
    }

    private fun drawSettings(x: Int, y0: Int, w: Int): Int {
        var y = y0
        val rowH = 26
        var advancedShown = false
        for (section in sections) {
            if (section.advanced && !advancedShown) {
                advancedShown = true
                y += 2
                val l = if (showAdvanced) "Hide advanced" else "Show advanced"
                val lw = (tw(l, 7f) + 18).toInt()
                val hov = inside(x, y, lw, 16)
                UiRecorder.chevron(x + 4f, y + 8f, showAdvanced, if (hov) Theme.TEXT else Theme.SUB)
                text(l, x + 14, y + 4.5f, 7f, if (hov) Theme.TEXT else Theme.SUB)
                hit(x, y, lw, 16) { showAdvanced = !showAdvanced }
                y += 22
            }
            if (section.advanced && !showAdvanced) continue
            label(section.title, x, y)
            y += 10
            for ((i, s) in section.items.withIndex()) {
                if (i > 0) Theme.rect(x, y, w, 1, Theme.withAlpha(Theme.BORDER, 0x60))
                settingRow(s, x, y, w, rowH)
                y += rowH
            }
            y += 14
        }
        if (dirty && focused == null) { cfg.save(); dirty = false }
        return y - y0
    }

    private fun settingRow(s: Setting, x: Int, y: Int, w: Int, h: Int) {
        val dim = s is Num && !s.enabled()
        val yc = y + h / 2
        val labelW = tw(s.label, 7.3f)
        text(s.label, x, yc - 4, 7.3f, if (dim) Theme.DIM else Theme.TEXT)
        if (inside(x, y, (labelW + 8).toInt(), h)) tip(s.help)
        (s as? Toggle)?.live?.invoke()?.let { live -> text(live, x + labelW + 10, yc - 3.3f, 6.3f, Theme.DIM) }
        val right = x + w
        when (s) {
            is Toggle -> {
                toggle(s.get(), right - 22, yc - 6) { s.set(!s.get()); dirty = true }
                if (s.label == "Practice mode" && confirmAction == "live" && System.currentTimeMillis() < confirmUntil) {
                    button("Go live", right - 90, yc - 8, 60, 16, Style.DANGER) { setPractice(false, true) }
                }
            }
            is Range -> {
                val v = s.get()
                val vt = s.fmt(v)
                text(vt, right - tw(vt, 7f), yc - 3.5f, 7f, Theme.TEXT_SOFT)
                slider(v, s.lo, s.hi, s.step, right - 140, yc - 3, 90) { nv -> s.set(nv); dirty = true }
            }
            is Num -> if (!s.enabled()) {
                text("auto", right - tw("auto", 7f), yc - 3.5f, 7f, Theme.DIM)
            } else {
                val listener: (String) -> Unit = { txt ->
                    val d = txt.replace(",", "").replace("_", "").toDoubleOrNull()
                    if (d != null && d >= s.min) { s.set(d); dirty = true }
                }
                val f = fields.getOrPut(s) { field(numText(s.get()), 16, listener) }
                if (focused !== f) {
                    val want = numText(s.get())
                    if (f.value != want) setQuietly(f, want, listener)
                }
                val bad = f.value.replace(",", "").replace("_", "").toDoubleOrNull()?.let { it < s.min } ?: true
                val fw = 64
                if (s.unit.isNotEmpty()) text(s.unit, right - tw(s.unit, 7f), yc - 3.5f, 7f, Theme.DIM)
                val fx = right - fw - (if (s.unit.isNotEmpty()) 12 else 0)
                drawField(f, fx, yc - 8, fw, 16, "0", bad)
            }
        }
    }

    private fun numText(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) String.format(Locale.ROOT, "%.0f", d)
        else String.format(Locale.ROOT, "%.2f", d).trimEnd('0').trimEnd('.')

    // ------------------------------------------------------------------ formatting

    private fun coins(v: Double, signed: Boolean = false): String {
        val s = ScreenUtil.formatCoins(Math.abs(v))
        return when {
            v < 0 -> "-$s"
            signed && v > 0 -> "+$s"
            else -> s
        }
    }

    /** Unit prices, compact but precise enough to compare: 3.15M, 812.4k, 950.1. */
    private fun price(v: Double): String = when {
        v >= 1e9 -> String.format(Locale.ROOT, "%.2fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.ROOT, "%.2fM", v / 1e6)
        v >= 1e3 -> String.format(Locale.ROOT, "%.1fk", v / 1e3)
        else -> String.format(Locale.ROOT, "%.1f", v)
    }

    private fun duration(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun ago(at: Long): String {
        val s = (System.currentTimeMillis() - at) / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            else -> "${s / 3600}h"
        }
    }
}
