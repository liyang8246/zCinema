package com.zcinema;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;

public class ZCinemaMod implements ModInitializer {
  public static final String MOD_ID = "zcinema";

  /** Max screen edge length (blocks) when scanning black concrete. */
  private static final int MAX_SCREEN_SIZE = 256;

  public static final Block CINEMA_CORE_BLOCK =
      Registry.register(
          BuiltInRegistries.BLOCK,
          ResourceLocation.fromNamespaceAndPath(MOD_ID, "cinema_core"),
          new CinemaCoreBlock(
              BlockBehaviour.Properties.ofFullCopy(Blocks.BLACK_CONCRETE).noLootTable()));

  public static final BlockEntityType<CinemaCoreBlockEntity> CINEMA_CORE_BE =
      Registry.register(
          BuiltInRegistries.BLOCK_ENTITY_TYPE,
          ResourceLocation.fromNamespaceAndPath(MOD_ID, "cinema_core"),
          BlockEntityType.Builder.of(CinemaCoreBlockEntity::new, CINEMA_CORE_BLOCK).build(null));

  public static final EntityType<EmojiEntity> EMOJI_ENTITY_TYPE =
      Registry.register(
          BuiltInRegistries.ENTITY_TYPE,
          ResourceLocation.fromNamespaceAndPath(MOD_ID, "emoji"),
          EntityType.Builder.<EmojiEntity>of(EmojiEntity::new, MobCategory.MISC)
              .sized(0.5F, 0.5F)
              .noSave()
              .clientTrackingRange(10)
              .updateInterval(1)
              .build());

  public static final EmojiItem[] EMOJI_ITEMS = new EmojiItem[EmojiItem.EMOJI_IDS.length];

  static {
    for (int i = 0; i < EmojiItem.EMOJI_IDS.length; i++) {
      EMOJI_ITEMS[i] =
          Registry.register(
              BuiltInRegistries.ITEM,
              ResourceLocation.fromNamespaceAndPath(MOD_ID, EmojiItem.EMOJI_IDS[i]),
              new EmojiItem(i, new Item.Properties().stacksTo(64)));
    }
    Registry.register(
        BuiltInRegistries.CREATIVE_MODE_TAB,
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "emojis"),
        FabricItemGroup.builder()
            .title(Component.translatable("itemGroup.zcinema.emojis"))
            .icon(() -> new ItemStack(EMOJI_ITEMS[0]))
            .displayItems(
                (params, output) -> {
                  for (EmojiItem item : EMOJI_ITEMS) {
                    output.accept(item);
                  }
                })
            .build());
  }

  @Override
  public void onInitialize() {
    PayloadTypeRegistry.playC2S().register(TimeSyncPayloads.Ping.TYPE, TimeSyncPayloads.Ping.CODEC);
    PayloadTypeRegistry.playS2C().register(TimeSyncPayloads.Pong.TYPE, TimeSyncPayloads.Pong.CODEC);
    PayloadTypeRegistry.playC2S().register(ScreenStatePayload.TYPE, ScreenStatePayload.CODEC);
    PayloadTypeRegistry.playS2C().register(ScreenStatePayload.TYPE, ScreenStatePayload.CODEC);

    ServerPlayNetworking.registerGlobalReceiver(
        TimeSyncPayloads.Ping.TYPE,
        (payload, context) ->
            context
                .responseSender()
                .sendPacket(
                    new TimeSyncPayloads.Pong(payload.clientSendMs(), System.currentTimeMillis())));

    ServerPlayNetworking.registerGlobalReceiver(
        ScreenStatePayload.TYPE,
        (payload, context) -> {
          ServerPlayer sender = context.player();
          context
              .server()
              .execute(
                  () -> {
                    Level level = sender.level();
                    if (payload.mediaPosMs() < 0L) {
                      if (level.getBlockEntity(payload.pos()) instanceof CinemaCoreBlockEntity) {
                        level.removeBlock(payload.pos(), false);
                      }
                      return;
                    }
                    if (level.getBlockEntity(payload.pos()) instanceof CinemaCoreBlockEntity be) {
                      long now = System.currentTimeMillis();
                      be.updateState(payload.url(), payload.playing(), payload.mediaPosMs(), now);
                      ScreenStatePayload sync =
                          new ScreenStatePayload(
                              be.getBlockPos(),
                              be.url,
                              be.playing,
                              be.mediaPosMs,
                              be.serverTimestampMs);
                      for (ServerPlayer player : PlayerLookup.all(context.server())) {
                        ServerPlayNetworking.send(player, sync);
                      }
                    }
                  });
        });

    UseBlockCallback.EVENT.register(ZCinemaMod::onUseBlock);

    PlayerBlockBreakEvents.AFTER.register(
        (level, player, pos, state, blockEntity) -> {
          if (!level.isClientSide) {
            CinemaCoreBlockEntity screen = CinemaCoreBlockEntity.findByAnyBlock(level, pos);
            if (screen != null) {
              level.removeBlock(screen.getBlockPos(), false);
            }
          }
        });
  }

  private static InteractionResult onUseBlock(
      Player player, Level level, InteractionHand hand, BlockHitResult hit) {
    if (player.isSpectator() || hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()) {
      return InteractionResult.PASS;
    }
    if (!player.getItemInHand(hand).is(Items.STICK)) {
      return InteractionResult.PASS;
    }

    BlockPos clickedPos = hit.getBlockPos();
    CinemaCoreBlockEntity existing = CinemaCoreBlockEntity.findByAnyBlock(level, clickedPos);
    if (existing != null) {
      if (level.isClientSide) {
        CinemaClientHooks.openControlScreen.accept(existing.getBlockPos());
      }
      return InteractionResult.SUCCESS;
    }

    if (!level.getBlockState(clickedPos).is(Blocks.BLACK_CONCRETE)) {
      return InteractionResult.PASS;
    }

    if (!level.isClientSide) {
      tryCreateScreen(level, player, clickedPos, hit.getDirection());
    }
    return InteractionResult.SUCCESS;
  }

  private static void tryCreateScreen(
      Level level, Player player, BlockPos clickedPos, Direction hitFace) {
    Direction facing =
        hitFace.getAxis().isHorizontal() ? hitFace : player.getDirection().getOpposite();
    Direction leftDir = facing.getClockWise();
    Direction rightDir = facing.getCounterClockWise();

    BlockPos rowLeft = clickedPos;
    for (int i = 0; i < MAX_SCREEN_SIZE; i++) {
      BlockPos next = rowLeft.relative(leftDir);
      if (!isFreeBlackConcrete(level, next)) break;
      rowLeft = next;
    }

    int width = 0;
    while (width < MAX_SCREEN_SIZE
        && isFreeBlackConcrete(level, rowLeft.relative(rightDir, width))) {
      width++;
    }
    if (width <= 0) return;

    BlockPos bottomLeft = rowLeft;
    for (int i = 0; i < MAX_SCREEN_SIZE; i++) {
      BlockPos candidate = bottomLeft.below();
      if (!isRowFreeBlackConcrete(level, candidate, rightDir, width)) break;
      bottomLeft = candidate;
    }

    int height = 0;
    while (height < MAX_SCREEN_SIZE
        && isRowFreeBlackConcrete(level, bottomLeft.above(height), rightDir, width)) {
      height++;
    }
    if (height <= 0) return;

    // Core BlockEntity sits 1 block left of the screen's bottom-left corner
    BlockPos corePos = bottomLeft.relative(leftDir);
    if (!level.getBlockState(corePos).isAir() && !level.getBlockState(corePos).canBeReplaced()) {
      player.displayClientMessage(Component.literal("§c无法创建屏幕：屏幕左下角往左一格的位置需留空"), true);
      return;
    }

    level.setBlock(corePos, CINEMA_CORE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
    if (level.getBlockEntity(corePos) instanceof CinemaCoreBlockEntity be) {
      be.width = width;
      be.height = height;
      be.facing = facing;
      be.setChanged();
      level.sendBlockUpdated(corePos, be.getBlockState(), be.getBlockState(), Block.UPDATE_ALL);
      player.displayClientMessage(
          Component.literal("§a已创建 " + width + "x" + height + " 放映屏幕"), true);
    }
  }

  private static boolean isRowFreeBlackConcrete(
      Level level, BlockPos rowStart, Direction rightDir, int width) {
    for (int x = 0; x < width; x++) {
      if (!isFreeBlackConcrete(level, rowStart.relative(rightDir, x))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isFreeBlackConcrete(Level level, BlockPos pos) {
    return level.getBlockState(pos).is(Blocks.BLACK_CONCRETE)
        && CinemaCoreBlockEntity.findByAnyBlock(level, pos) == null;
  }
}
