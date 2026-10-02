package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, ZCinema.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_TABS.register("tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.zcinema"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> ModItems.SCREEN_CORE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> output.accept(ModItems.SCREEN_CORE_ITEM.get()))
            .build());

    private ModCreativeTabs() {}
}
