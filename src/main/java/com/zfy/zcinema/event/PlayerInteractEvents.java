package com.zfy.zcinema.event;

import com.zfy.zcinema.block.ScreenCoreBlock;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Screen building: build a flat wall of black concrete, then hold a stick and sneak right-click
 * any block of it. The mod flood-fills the connected concrete, verifies it is flat and turns the
 * clicked block into a screen core for the whole rectangle, facing the player.
 *
 * <ul>
 *   <li>stick + sneak + right-click concrete: register the screen (or open it if it already has one)</li>
 *   <li>right-click a core (no stick): open the control panel</li>
 *   <li>right-click a core with a stick: remove the screen again</li>
 * </ul>
 */
public final class PlayerInteractEvents {
    private PlayerInteractEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(new PlayerInteractEvents());
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player player = event.getEntity();
        if (player == null) return;
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        boolean core = state.is(ModBlocks.SCREEN_CORE.get());
        boolean material = ScreenDetector.isScreenMaterial(state);
        if (!core && !material) return;

        boolean stick = holdsStick(player, event.getHand());
        boolean sneak = player.isShiftKeyDown();
        Direction hitFace = event.getHitVec() instanceof BlockHitResult hit ? hit.getDirection() : Direction.UP;
        Vec3 eye = player.getEyePosition();

        if (core) {
            // A stick on a core removes the screen; anything else opens its control panel.
            if (stick) {
                level.setBlockAndUpdate(pos, Blocks.BLACK_CONCRETE.defaultBlockState());
                tell(player, "message.zcinema.removed");
            } else if (level.getBlockEntity(pos) instanceof CinemaScreenBlockEntity be) {
                openPanel(player, be);
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
            return;
        }
        if (material && sneak && (stick || player.getItemInHand(event.getHand()).isEmpty())) {
            CinemaScreenBlockEntity existing = findCore(level, pos, hitFace, eye);
            if (existing != null) {
                openPanel(player, existing);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.CONSUME);
                return;
            }
            ScreenArea area = ScreenDetector.detect(level, pos, hitFace, eye);
            if (area == null) {
                tell(player, "message.zcinema.not_flat");
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.CONSUME);
                return;
            }
            Direction facing = area.normal().getAxis().isHorizontal()
                    ? area.normal().getOpposite()
                    : Direction.NORTH;
            BlockState newState = ModBlocks.SCREEN_CORE.get().defaultBlockState()
                    .setValue(ScreenCoreBlock.FACING, facing);
            level.setBlockAndUpdate(pos, newState);
            if (level.getBlockEntity(pos) instanceof CinemaScreenBlockEntity be) {
                be.setScreenArea(area);
                be.broadcastState();
                tell(player, "message.zcinema.created", area.screenWidth(), area.screenHeight());
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
        }
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
        if (player instanceof ServerPlayer server) {
            server.openMenu(be, buffer -> buffer.writeBlockPos(be.getBlockPos()));
        }
    }

    private static void tell(Player player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }
}
