package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.block.ScreenBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {
    /**
     * The screen surface: every block of a registered screen is this block. Its properties are
     * copied straight from black concrete, so it looks, sounds, breaks and drops identically.
     */
    public static final Block SCREEN = Registry.register(BuiltInRegistries.BLOCK, ZCinema.id("screen"),
            new ScreenBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BLACK_CONCRETE).forceSolidOn()));

    private ModBlocks() {}

    /** Loading this holder class is what performs the registration. */
    public static void register() {
    }
}
