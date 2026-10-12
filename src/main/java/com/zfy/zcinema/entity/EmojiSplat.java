package com.zfy.zcinema.entity;

import com.zfy.zcinema.registry.ModEntities;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An emoji picture hanging on a surface: a flat quad three blocks wide and three blocks tall, the
 * centre of it sitting on the face it was thrown at.
 *
 * <p>It never moves and never saves - it is a throwaway souvenir that fades out after a while.
 */
public class EmojiSplat extends Entity {
    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(EmojiSplat.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Byte> DATA_FACE =
            SynchedEntityData.defineId(EmojiSplat.class, EntityDataSerializers.BYTE);

    /** The picture is three blocks across and three blocks tall. */
    public static final float SIZE = 3.0F;
    /** How long a picture stays on the wall, in ticks. */
    public static final int LIFETIME = 20 * 60 * 5;
    /** The last ticks of that fade the picture out instead of popping it. */
    public static final int FADE_TICKS = 20;
    /** Half thickness of the culling box, whose other two extents cover the whole picture. */
    private static final double CULL_THICKNESS = 0.05D;

    public EmojiSplat(EntityType<? extends EmojiSplat> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public EmojiSplat(Level level, Vec3 center, Direction face, ItemStack emoji) {
        this(ModEntities.EMOJI_SPLAT, level);
        this.setPos(center);
        this.setItem(emoji);
        this.setFace(face);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ITEM, ItemStack.EMPTY);
        builder.define(DATA_FACE, (byte) Direction.UP.get3DDataValue());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.tickCount > LIFETIME) {
            this.discard();
        }
    }

    public ItemStack item() {
        return this.entityData.get(DATA_ITEM);
    }

    public void setItem(ItemStack stack) {
        this.entityData.set(DATA_ITEM, stack.copy());
    }

    public Direction face() {
        return Direction.from3DDataValue(this.entityData.get(DATA_FACE));
    }

    public void setFace(Direction face) {
        this.entityData.set(DATA_FACE, (byte) face.get3DDataValue());
    }

    /**
     * The picture reaches a block and a half out from the entity in the two directions along the
     * wall, so the default body-sized culling box has to be widened or the quad pops in and out of
     * view as the camera turns.
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double half = SIZE * 0.5D;
        Direction.Axis axis = this.face().getAxis();
        double dx = axis == Direction.Axis.X ? CULL_THICKNESS : half;
        double dy = axis == Direction.Axis.Y ? CULL_THICKNESS : half;
        double dz = axis == Direction.Axis.Z ? CULL_THICKNESS : half;
        return new AABB(this.getX() - dx, this.getY() - dy, this.getZ() - dz,
                this.getX() + dx, this.getY() + dy, this.getZ() + dz);
    }

    /** Nothing to save: the entity type is registered as unsaved. */
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isPickable() {
        return false;
    }
}
