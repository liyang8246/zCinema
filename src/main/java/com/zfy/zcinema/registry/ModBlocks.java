package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.block.ScreenBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ZCinema.MODID);

    /**
     * The screen surface: every block of a registered screen is this block. Its properties are
     * copied straight from black concrete, so it looks, sounds, breaks and drops identically.
     */
    public static final DeferredBlock<Block> SCREEN = BLOCKS.registerBlock("screen", ScreenBlock::new, () ->
            BlockBehaviour.Properties.ofFullCopy(Blocks.BLACK_CONCRETE).forceSolidOn());

    private ModBlocks() {}
}
