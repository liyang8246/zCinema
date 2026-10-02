package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.block.ScreenCoreBlock;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ZCinema.MODID);

    /** Presented as plain black concrete: pick-block and drops always restore concrete. */
    public static final DeferredItem<BlockItem> SCREEN_CORE_ITEM = ITEMS.registerSimpleBlockItem("screen_core", ModBlocks.SCREEN_CORE);

    private ModItems() {}
}
