package com.zfy.zcinema.mixin;

import com.zfy.zcinema.item.EmojiItem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Halves the right-click repeat delay while an emoji is in the main hand, which is what doubles the
 * rate you can keep flinging them at by holding the button. Vanilla resets that delay to four ticks
 * on every use; everything that is not an emoji keeps it.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftEmojiThrowMixin {
    @Shadow
    private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("TAIL"))
    private void zcinema$fasterEmojiThrow(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.player != null && minecraft.player.getMainHandItem().getItem() instanceof EmojiItem) {
            this.rightClickDelay = 2;
        }
    }
}
