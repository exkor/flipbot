package com.exkor.flipbot;

import com.exkor.flipbot.flipper.FlipperMod;
import com.exkor.flipbot.flipper.trading.BazaarChatListener;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers the /flipbot commands, the toggle key, the status HUD, the tick handler and the Bazaar chat listener. */
public final class FlipBotClient implements ClientModInitializer {
    public static final String MOD_ID = "flipbot";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping toggleKey;
    private static KeyMapping menuKey;

    @Override
    public void onInitializeClient() {
        FlipperMod.init();
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        // Unbound by default so it can't clash with other mods' keys; set it in Controls.
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flipbot.toggle", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.flipbot.menu", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> Commands.register(dispatcher));
        ClientTickEvents.END_CLIENT_TICK.register(FlipBotClient::onTick);
        BazaarChatListener.init();
        com.exkor.flipbot.craft.CraftFlipper.init();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), (graphics, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            String line = FlipperMod.hudLine();
            if (line == null || mc.player == null) return;
            int w = mc.font.width(line) + 8;
            graphics.fill(2, 2, 2 + w, 14, 0x90000000);
            graphics.text(mc.font, line, 6, 4, 0xFFFFFFFF, false);
        });
        LOG.info("FlipBot loaded");
    }

    private static int purseTicks;

    private static void onTick(Minecraft client) {
        SelfTest.onTick(client);
        com.exkor.flipbot.craft.CraftFlipper.tick(client);
        // Keep the last known purse fresh (every 2 s) for the menu, /flipbot flips and the bankroll.
        if (client.player != null && ++purseTicks >= 40) {
            purseTicks = 0;
            com.exkor.flipbot.flipper.trading.ScreenUtil.readPurse();
        }
        while (toggleKey.consumeClick()) {
            FlipperMod.toggle();
        }
        while (menuKey.consumeClick()) {
            if (client.gui.screen() == null) {
                com.exkor.flipbot.ui.FlipBotScreen.open();
            }
        }
    }
}
