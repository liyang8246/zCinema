package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.gui.CinemaScreenMenu;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;

public final class ModMenus {
    public static final MenuType<CinemaScreenMenu> SCREEN = Registry.register(
            BuiltInRegistries.MENU, ZCinema.id("screen"),
            new ExtendedScreenHandlerType<>(
                    (syncId, inventory, pos) -> CinemaScreenMenu.client(syncId, inventory, pos),
                    BlockPos.STREAM_CODEC));

    private ModMenus() {}

    /** Loading this holder class is what performs the registration. */
    public static void register() {
    }
}
