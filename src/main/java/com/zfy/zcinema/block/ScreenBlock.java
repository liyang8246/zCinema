package com.zfy.zcinema.block;

import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * One block of a cinema screen.
 *
 * <p>Registering a black concrete wall turns <em>every</em> block of the rectangle into this block;
 * the wall and the screen are then the same thing, and there is no hidden "core" carved out of it.
 * It is deliberately indistinguishable from black concrete - same model and texture, same hardness
 * and sound, same drops (copied from the vanilla block) and the same pick-block result - so an idle
 * screen simply looks like the dark wall it replaced.
 *
 * <p>The playback state lives in the block entity of one of these blocks (whichever block was used
 * to register the screen). Every other block of the screen also carries an empty block entity; the
 * server tick ignores those, and the renderer only draws the one that actually knows a rectangle.
 * A stick is required for every screen gesture, which
 * {@link com.zfy.zcinema.event.PlayerInteractEvents} enforces: sneak + right-click concrete to
 * register the wall, sneak + right-click a screen to open its panel. Taking the screen down is a
 * button in that panel - no world gesture can do it by accident.
 */
public class ScreenBlock extends Block implements EntityBlock {
    public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);

    public ScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CinemaScreenBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != ModBlockEntities.SCREEN_BE) return null;
        if (level.isClientSide()) return null;
        return (lvl, pos, st, be) -> ((CinemaScreenBlockEntity) be).serverTick();
    }

    /** Pick-block and inventory display always show plain black concrete. */
    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(Blocks.BLACK_CONCRETE);
    }

    @Override
    protected void spawnDestroyParticles(Level level, Player player, BlockPos pos, BlockState state) {
    }
}
