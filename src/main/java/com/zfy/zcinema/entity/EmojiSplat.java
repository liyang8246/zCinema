package com.zfy.zcinema.entity;

import com.zfy.zcinema.registry.ModEntities;
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
 * The emoji picture a throw leaves behind: a flat, upright image three blocks wide and three
 * blocks tall that always turns to face whoever is looking at it. It shoots two blocks upward over
 * a second and fades out right after.
 *
 * <p>It never saves - it is a throwaway souvenir, gone a second and a half after it appears.
 */
public class EmojiSplat extends Entity {
    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(EmojiSplat.class, EntityDataSerializers.ITEM_STACK);

    /** The picture is three blocks across and three blocks tall. */
    public static final float SIZE = 3.0F;

    /** How far it floats up after spawning, and how long that takes: two blocks in a second. */
    public static final double RISE_BLOCKS = 2.0D;

    public static final int RISE_TICKS = 20;
    private static final double RISE_PER_TICK = RISE_BLOCKS / RISE_TICKS;

    /** The last ticks of its life fade the picture out instead of popping it. */
    public static final int FADE_TICKS = 10;

    /** Rise, then fade: the picture is gone about a second and a half after it appears. */
    public static final int LIFETIME = RISE_TICKS + FADE_TICKS;

    public EmojiSplat(EntityType<? extends EmojiSplat> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public EmojiSplat(Level level, Vec3 center, ItemStack emoji) {
        this(ModEntities.EMOJI_SPLAT, level);
        this.setPos(center);
        this.setItem(emoji);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ITEM, ItemStack.EMPTY);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.tickCount > LIFETIME) {
            this.discard();
            return;
        }
        // The rise is a server side move: clients get it through the tracker and interpolate.
        if (!this.level().isClientSide && this.tickCount <= RISE_TICKS) {
            this.setPos(this.getX(), this.getY() + RISE_PER_TICK, this.getZ());
        }
    }

    public ItemStack item() {
        return this.entityData.get(DATA_ITEM);
    }

    public void setItem(ItemStack stack) {
        this.entityData.set(DATA_ITEM, stack.copy());
    }

    /**
     * The image is drawn as a billboard, so which way it happens to face changes with the viewer:
     * cull against a box that covers the whole picture wherever it is seen from, plus the height it
     * floats up to, or it would pop in and out of view as the camera turns.
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double half = SIZE * 0.5D;
        return new AABB(
                this.getX() - half,
                this.getY() - half,
                this.getZ() - half,
                this.getX() + half,
                this.getY() + half + RISE_BLOCKS,
                this.getZ() + half);
    }

    /** Nothing to save: the entity type is registered as unsaved. */
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public boolean isPickable() {
        return false;
    }
}
