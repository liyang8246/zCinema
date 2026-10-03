package com.zfy.zcinema.mixin;

import com.zfy.zcinema.client.CrosshairHider;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Simply skips the crosshair layer while {@link CrosshairHider} says the view has been still. */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(
            method = "renderCrosshair(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void zcinema$hideIdleCrosshair(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (CrosshairHider.shouldHide()) {
            ci.cancel();
        }
    }
}
