package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.gui.CinemaScreenMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ZCinema.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<CinemaScreenMenu>> SCREEN =
            MENUS.register("screen", () -> IMenuTypeExtension.create((IContainerFactory<CinemaScreenMenu>)
                    (id, inventory, buffer) -> CinemaScreenMenu.client(id, inventory, buffer.readBlockPos())));

    private ModMenus() {}
}
