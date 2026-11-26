package com.exkor.flipbot.ui

import com.exkor.flipbot.FlipBotClient

/** Error logging for the UI code. */
object MenuDiag {
    @JvmField val LOGGER = FlipBotClient.LOG

    @JvmStatic
    fun fail(code: String, message: String) = fail(code, message, null)

    @JvmStatic
    fun fail(code: String, message: String, t: Throwable?) {
        if (t != null) LOGGER.warn("[ui {}] {}", code, message, t) else LOGGER.warn("[ui {}] {}", code, message)
    }

    inline fun check(ok: Boolean, code: String, message: () -> String): Boolean {
        if (!ok) fail(code, message())
        return ok
    }

    inline fun <T> guard(code: String, message: String, block: () -> T): T? =
        try { block() } catch (t: Throwable) { fail(code, message, t); null }
}
