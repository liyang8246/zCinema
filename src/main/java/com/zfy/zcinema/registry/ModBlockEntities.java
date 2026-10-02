package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ZCinema.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CinemaScreenBlockEntity>> SCREEN_BE =
            BLOCK_ENTITIES.register("screen_core", () -> new BlockEntityType<>(
                    CinemaScreenBlockEntity::new, Set.of(ModBlocks.SCREEN_CORE.get())));

    private ModBlockEntities() {}
}
