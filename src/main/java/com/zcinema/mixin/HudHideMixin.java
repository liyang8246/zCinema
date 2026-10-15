package com.zcinema.mixin;

import com.zcinema.client.ZCinemaClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class HudHideMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void zcinema$hideHudWhenStill(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (ZCinemaClient.shouldHideHud()) {
            ci.cancel();
        }
    }
}
