package com.exkor.flipbot;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Prints a prefixed line in the local chat only. */
public final class Chat {
    public static final String PREFIX = "§6[FlipBot] §r";

    private Chat() {}

    public static void message(String text) {
        message(Component.literal(PREFIX + text));
    }

    public static void message(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || message == null) return;
        client.execute(() -> {
            try {
                if (client.gui != null) client.gui.chatListener().handleSystemMessage(message, false);
            } catch (RuntimeException e) {
                FlipBotClient.LOG.info("[chat not ready] {}", message.getString());
            }
        });
    }
}
