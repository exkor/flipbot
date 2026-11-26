package com.exkor.flipbot.ui

import net.minecraft.client.Minecraft

/** Called after vanilla's GUI pass; lets the open menu screen paint its vector layer on top. */
object MenuOverlay {
    @JvmStatic
    fun paint() {
        try {
            (Minecraft.getInstance().gui.screen() as? HasUiOverlay)?.paintUiOverlay()
        } catch (t: Throwable) {
            MenuDiag.fail("MenuOverlay.1", "menu paint failed", t)
        }
    }
}
