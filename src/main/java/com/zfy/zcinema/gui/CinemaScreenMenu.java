package com.zfy.zcinema.gui;

import com.zfy.zcinema.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Empty menu for the screen. It only exists so the GUI can be opened server-authorised. */
public class CinemaScreenMenu extends AbstractContainerMenu {
    private final BlockPos pos;

    public static CinemaScreenMenu server(int id, Inventory inventory, BlockPos pos) {
        return new CinemaScreenMenu(id, inventory, pos);
    }

    public static CinemaScreenMenu client(int id, Inventory inventory, BlockPos pos) {
        return new CinemaScreenMenu(id, inventory, pos);
    }

    public CinemaScreenMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.SCREEN.get(), id);
        this.pos = pos;
    }

    public BlockPos pos() {
        return pos;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(pos) instanceof com.zfy.zcinema.blockentity.CinemaScreenBlockEntity be
                && be.canControl(player);
    }
}
