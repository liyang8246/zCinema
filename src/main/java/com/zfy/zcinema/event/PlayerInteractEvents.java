package com.zfy.zcinema.event;

import com.zfy.zcinema.block.ScreenCoreBlock;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Everything on a screen is done with a stick in hand, and nothing without one:
 *
 * <ul>
 *   <li>stick + sneak + right-click concrete: register the wall as a screen (or open the panel of
 *       the screen it already belongs to)</li>
 *   <li>stick + sneak + right-click a core: open the control panel</li>
 *   <li>stick + right-click a core (no sneak): take the screen down again</li>
 * </ul>
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
        boolean core = state.is(ModBlocks.SCREEN_CORE.get());
        boolean material = ScreenDetector.isScreenMaterial(state);
        if (!core && !material) return;

        // The stick is part of every gesture. Its absence must also stop the core block from
        // opening anything by itself, otherwise the panel leaks out without it.
        if (!holdsStick(player, event.getHand())) return;
        boolean sneak = player.isShiftKeyDown();

        if (core && !sneak) {
            // Stick on the core without sneaking: take the screen down and restore the concrete.
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.setBlockAndUpdate(pos, Blocks.BLACK_CONCRETE.defaultBlockState());
                tell(player, "message.zcinema.removed");
            }
            consume(event);
            return;
        }
        if (!sneak) return;

        if (!(level instanceof ServerLevel serverLevel)) {
            // Client side only predicts the gesture; the server owns what actually happens.
            consume(event);
            return;
        }

        // Sneak + stick: open the panel when this wall already is a screen, otherwise register it.
        Direction hitFace = event.getHitVec() instanceof BlockHitResult hit ? hit.getDirection() : Direction.UP;
        Vec3 eye = player.getEyePosition();
        CinemaScreenBlockEntity existing = core
                ? (serverLevel.getBlockEntity(pos) instanceof CinemaScreenBlockEntity be ? be : null)
                : findCore(serverLevel, pos, hitFace, eye);
        if (existing != null) {
            openPanel(player, existing);
            consume(event);
            return;
        }
        if (!core) {
            ScreenArea area = ScreenDetector.detect(serverLevel, pos, hitFace, eye);
            if (area == null) {
                tell(player, "message.zcinema.not_flat");
                consume(event);
                return;
            }
            Direction facing = area.normal().getAxis().isHorizontal()
                    ? area.normal().getOpposite()
                    : Direction.NORTH;
            BlockState newState = ModBlocks.SCREEN_CORE.get().defaultBlockState()
                    .setValue(ScreenCoreBlock.FACING, facing);
            serverLevel.setBlockAndUpdate(pos, newState);
            if (serverLevel.getBlockEntity(pos) instanceof CinemaScreenBlockEntity be) {
                be.setScreenArea(area);
                be.broadcastState();
                tell(player, "message.zcinema.created", area.screenWidth(), area.screenHeight());
            }
        }
        consume(event);
    }

    private static void consume(PlayerInteractEvent.RightClickBlock event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
    }

    private static boolean holdsStick(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return stack.is(Items.STICK);
    }

    private static CinemaScreenBlockEntity findCore(ServerLevel level, BlockPos origin, Direction hitFace, Vec3 eye) {
        ScreenArea area = ScreenDetector.detect(level, origin, hitFace, eye);
        if (area == null) return null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = area.min().getX(); x <= area.max().getX(); x++) {
            for (int y = area.min().getY(); y <= area.max().getY(); y++) {
                for (int z = area.min().getZ(); z <= area.max().getZ(); z++) {
                    cursor.set(x, y, z);
                    if (level.getBlockEntity(cursor) instanceof CinemaScreenBlockEntity be) {
                        return be;
                    }
                }
            }
        }
        return null;
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
