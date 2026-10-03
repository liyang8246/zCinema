package com.zfy.zcinema.event;

import com.zfy.zcinema.block.ScreenBlock;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.gui.CinemaScreenMenu;
import com.zfy.zcinema.screen.ScreenArea;
import com.zfy.zcinema.screen.ScreenDetector;
import com.zfy.zcinema.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Stick + sneak + right-click is the only world gesture a screen has:
 *
 * <ul>
 *   <li>on black concrete: register the connected flat wall as a screen - every block of the
 *       rectangle becomes a screen block (which looks exactly like the concrete it replaces)</li>
 *   <li>on a screen block: open the control panel</li>
 * </ul>
 *
 * <p>There is deliberately no gesture that takes a screen down: that is the panel's button, so a
 * stray right-click can never dismantle a wall.
 *
 * <p>Right-clicking a screen without a stick stays vanilla, so blocks still place against it.
 * The handler runs on both sides: the server does the work, the client cancels its own prediction
 * so it does not place a block or try the other hand after the gesture.
 */
public final class PlayerInteractEvents {
    private PlayerInteractEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(new PlayerInteractEvents());
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player == null) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!ScreenDetector.isScreenMaterial(state)) return;

        // The stick is part of every gesture; without it the screen is just a wall.
        if (!holdsStick(player, event.getHand())) return;
        if (!player.isShiftKeyDown()) return;

        if (!(level instanceof ServerLevel serverLevel)) {
            // Client side only predicts the gesture; the server owns what actually happens.
            consume(event);
            return;
        }

        Direction hitFace = event.getHitVec() instanceof BlockHitResult hit ? hit.getDirection() : Direction.UP;
        Vec3 eye = player.getEyePosition();
        ScreenArea area = ScreenDetector.detect(serverLevel, pos, hitFace, eye);
        if (area == null) {
            tell(player, "message.zcinema.not_flat");
            consume(event);
            return;
        }

        CinemaScreenBlockEntity existing = findScreen(serverLevel, area);
        if (existing != null) {
            openPanel(player, existing);
            consume(event);
            return;
        }

        // A fresh wall: turn the whole rectangle into screen blocks, then let the clicked block
        // carry the playback state.
        installScreenBlocks(serverLevel, area);
        if (serverLevel.getBlockEntity(pos) instanceof CinemaScreenBlockEntity be) {
            be.setScreenArea(area);
            be.broadcastState();
        }
        tell(player, "message.zcinema.created", area.screenWidth(), area.screenHeight());
        consume(event);
    }

    /** Replaces every block of the detected rectangle that is screen material with the screen block. */
    private static void installScreenBlocks(ServerLevel level, ScreenArea area) {
        Direction facing = area.normal().getAxis().isHorizontal()
                ? area.normal().getOpposite()
                : Direction.NORTH;
        BlockState screen = ModBlocks.SCREEN.get().defaultBlockState().setValue(ScreenBlock.FACING, facing);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = area.min().getX(); x <= area.max().getX(); x++) {
            for (int y = area.min().getY(); y <= area.max().getY(); y++) {
                for (int z = area.min().getZ(); z <= area.max().getZ(); z++) {
                    cursor.set(x, y, z);
                    BlockState current = level.getBlockState(cursor);
                    // Only the material the detector found is converted: a rectangle drawn around an
                    // irregular wall must not swallow whatever else stands in its corners.
                    if (ScreenDetector.isScreenMaterial(current) && !current.is(ModBlocks.SCREEN.get())) {
                        level.setBlockAndUpdate(cursor, screen);
                    }
                }
            }
        }
    }

    /**
     * The screen that owns this rectangle, if the wall already is one. Only one block of a screen
     * carries the playback state - every other screen block has an empty block entity - so look
     * for the one that knows a rectangle.
     */
    private static CinemaScreenBlockEntity findScreen(ServerLevel level, ScreenArea area) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = area.min().getX(); x <= area.max().getX(); x++) {
            for (int y = area.min().getY(); y <= area.max().getY(); y++) {
                for (int z = area.min().getZ(); z <= area.max().getZ(); z++) {
                    cursor.set(x, y, z);
                    if (level.getBlockEntity(cursor) instanceof CinemaScreenBlockEntity be && be.hasScreenArea()) {
                        return be;
                    }
                }
            }
        }
        return null;
    }

    private static void consume(PlayerInteractEvent.RightClickBlock event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
    }

    private static boolean holdsStick(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return stack.is(Items.STICK);
    }

    private static void openPanel(Player player, CinemaScreenBlockEntity be) {
        if (!(player instanceof ServerPlayer server)) return;
        // A double-triggered interact must not close and reopen the panel: only open it when the
        // player is not already looking at this very screen.
        if (server.containerMenu instanceof CinemaScreenMenu menu && menu.pos().equals(be.getBlockPos())) {
            return;
        }
        server.openMenu(be, buffer -> buffer.writeBlockPos(be.getBlockPos()));
    }

    private static void tell(Player player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }
}
