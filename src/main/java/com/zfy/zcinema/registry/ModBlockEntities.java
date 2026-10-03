package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
    public static final BlockEntityType<CinemaScreenBlockEntity> SCREEN_BE = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, ZCinema.id("screen"),
            BlockEntityType.Builder.of(CinemaScreenBlockEntity::new, ModBlocks.SCREEN).build(null));

    private ModBlockEntities() {}

    /** Loading this holder class is what performs the registration. */
    public static void register() {
    }
}
