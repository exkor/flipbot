package com.exkor.flipbot.mixin;

import com.exkor.flipbot.ui.MenuOverlay;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paints the FlipBot menu's vector layer (rounded cards, Inter text) right after vanilla draws the GUI. */
@Mixin(GameRenderer.class)
public class GameRendererUiMixin {
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.AFTER))
	private void flipbot$paintMenu(net.minecraft.client.DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
		MenuOverlay.paint();
	}
}
