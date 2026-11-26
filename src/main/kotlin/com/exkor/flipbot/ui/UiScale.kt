package com.exkor.flipbot.ui

import net.minecraft.client.Minecraft

/** Virtual-pixel scale for the vector UI: the same size at every GUI scale. */
object UiScale {
    private const val REFERENCE_GUI_SCALE = 2.0
    private const val BASE = 0.77f * 1.30f

    @JvmField var user = 1f

    fun factor(): Float {
        val guiScale = Minecraft.getInstance().window.guiScale
        if (guiScale <= 0) MenuDiag.fail("UiScale.1", "window guiScale is $guiScale")
        val compensation = (REFERENCE_GUI_SCALE / guiScale).coerceAtMost(1.0)
        return (compensation * BASE).toFloat() * user
    }

    fun vx(real: Number): Int = (real.toDouble() / factor()).toInt()
}
