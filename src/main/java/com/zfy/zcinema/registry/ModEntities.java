package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.entity.EmojiSplat;
import com.zfy.zcinema.entity.ThrownEmoji;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    public static final EntityType<ThrownEmoji> THROWN_EMOJI = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, ZCinema.id("thrown_emoji"),
            EntityType.Builder.<ThrownEmoji>of(ThrownEmoji::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build(ZCinema.id("thrown_emoji").toString()));

    public static final EntityType<EmojiSplat> EMOJI_SPLAT = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, ZCinema.id("emoji_splat"),
            EntityType.Builder.<EmojiSplat>of(EmojiSplat::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(8)
                    .noSave()
                    .build(ZCinema.id("emoji_splat").toString()));

    private ModEntities() {}

    /** Loading this holder class is what performs the registration. */
    public static void register() {
    }
}
