package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.block.ScreenCoreBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ZCinema.MODID);

    /**
     * The screen core: looks and behaves exactly like black concrete, but carries the playback
     * state of the multi-block screen it heads. Mining it turns it back into plain concrete.
     */
    public static final DeferredBlock<Block> SCREEN_CORE = BLOCKS.registerBlock("screen_core", ScreenCoreBlock::new, () ->
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5f)
                    .sound(SoundType.STONE)
                    .forceSolidOn());

    private ModBlocks() {}
}
