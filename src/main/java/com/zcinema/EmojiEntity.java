package com.zcinema;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class EmojiEntity extends Entity {
  // 5x vanilla egg throw speed (1.5F * 5 = 7.5F)
  public static final float THROW_SPEED = 1.5F * 5.0F;
  public static final float LANDED_DURATION_SEC = 2.0F;
  public static final float FADE_START_SEC = 1.5F;
  public static final float RISE_DISTANCE_BLOCKS = 2.0F;
  public static final double WALL_OFFSET_BLOCKS = 2.0D;

  private static final EntityDataAccessor<Integer> DATA_EMOJI_ID =
      SynchedEntityData.defineId(EmojiEntity.class, EntityDataSerializers.INT);
  private static final EntityDataAccessor<Vector3f> DATA_VELOCITY =
      SynchedEntityData.defineId(EmojiEntity.class, EntityDataSerializers.VECTOR3);
  private static final EntityDataAccessor<Boolean> DATA_LANDED =
      SynchedEntityData.defineId(EmojiEntity.class, EntityDataSerializers.BOOLEAN);
  private static final EntityDataAccessor<Vector3f> DATA_LANDED_POS =
      SynchedEntityData.defineId(EmojiEntity.class, EntityDataSerializers.VECTOR3);

  private int landedTicks = 0;
  private long clientLandedStartMs = 0L;

  public EmojiEntity(EntityType<? extends EmojiEntity> type, Level level) {
    super(type, level);
    this.noPhysics = true;
  }

  public EmojiEntity(Level level, Player shooter, int emojiIndex) {
    this(ZCinemaMod.EMOJI_ENTITY_TYPE, level);
    this.setPos(shooter.getX(), shooter.getEyeY() - 0.1D, shooter.getZ());
    this.entityData.set(DATA_EMOJI_ID, Mth.clamp(emojiIndex, 0, EmojiItem.EMOJI_IDS.length - 1));

    Vec3 dir =
        Vec3.directionFromRotation(shooter.getXRot(), shooter.getYRot())
            .normalize()
            .scale(THROW_SPEED);
    this.setDeltaMovement(dir);
    this.entityData.set(DATA_VELOCITY, dir.toVector3f());
  }

  @Override
  protected void defineSynchedData(SynchedEntityData.Builder builder) {
    builder.define(DATA_EMOJI_ID, 0);
    builder.define(DATA_VELOCITY, new Vector3f());
    builder.define(DATA_LANDED, false);
    builder.define(DATA_LANDED_POS, new Vector3f());
  }

  public int getEmojiIndex() {
    return Mth.clamp(this.entityData.get(DATA_EMOJI_ID), 0, EmojiItem.EMOJI_IDS.length - 1);
  }

  public boolean isLanded() {
    return this.entityData.get(DATA_LANDED);
  }

  public Vector3f getLandedPos() {
    return this.entityData.get(DATA_LANDED_POS);
  }

  public float getLandedElapsedSeconds(float partialTick) {
    if (!isLanded()) return 0.0F;
    if (this.level().isClientSide) {
      if (this.clientLandedStartMs == 0L) {
        this.clientLandedStartMs = System.currentTimeMillis();
      }
      return (System.currentTimeMillis() - this.clientLandedStartMs) / 1000.0F;
    }
    return (this.landedTicks + partialTick) / 20.0F;
  }

  public static float computeAlpha(float elapsedSec) {
    if (elapsedSec <= FADE_START_SEC) return 1.0F;
    if (elapsedSec >= LANDED_DURATION_SEC) return 0.0F;
    return (LANDED_DURATION_SEC - elapsedSec) / (LANDED_DURATION_SEC - FADE_START_SEC);
  }

  public static float computeRiseOffset(float elapsedSec) {
    return RISE_DISTANCE_BLOCKS * Mth.clamp(elapsedSec / LANDED_DURATION_SEC, 0.0F, 1.0F);
  }

  @Override
  public void tick() {
    super.tick();
    if (isLanded()) {
      if (this.level().isClientSide && this.clientLandedStartMs == 0L) {
        this.clientLandedStartMs = System.currentTimeMillis();
      }
      Vector3f lp = getLandedPos();
      this.setPos(lp.x, lp.y, lp.z);
      this.landedTicks++;
      if (!this.level().isClientSide && this.landedTicks > 45) {
        this.discard();
      }
      return;
    }

    Vec3 start = this.position();
    Vector3f syncVel = this.entityData.get(DATA_VELOCITY);
    Vec3 vel = new Vec3(syncVel.x, syncVel.y, syncVel.z);
    Vec3 end = start.add(vel);

    BlockHitResult hit =
        this.level()
            .clip(
                new ClipContext(
                    start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));

    if (hit.getType() == HitResult.Type.BLOCK) {
      Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
      Vec3 basePos = hit.getLocation().add(normal.scale(WALL_OFFSET_BLOCKS));
      if (!this.level().isClientSide) {
        this.entityData.set(DATA_LANDED_POS, basePos.toVector3f());
        this.entityData.set(DATA_LANDED, true);
        this.entityData.set(DATA_VELOCITY, new Vector3f());
      } else {
        this.clientLandedStartMs = System.currentTimeMillis();
        this.entityData.set(DATA_LANDED_POS, basePos.toVector3f());
        this.entityData.set(DATA_LANDED, true);
      }
      this.setPos(basePos);
      return;
    }

    this.setPos(end);
    Vec3 nextVel = vel.scale(0.99D).subtract(0.0D, 0.03D, 0.0D);
    this.setDeltaMovement(nextVel);
    if (!this.level().isClientSide) {
      this.entityData.set(DATA_VELOCITY, nextVel.toVector3f());
      if (this.tickCount > 100) {
        this.discard();
      }
    } else {
      this.entityData.set(DATA_VELOCITY, nextVel.toVector3f());
    }
  }

  @Override
  public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
    // Ignore vanilla 3-tick position snaps; flight is driven by synced DATA_VELOCITY and landing by
    // DATA_LANDED_POS
  }

  @Override
  public boolean isPickable() {
    return false;
  }

  @Override
  public boolean isPushable() {
    return false;
  }

  @Override
  public boolean canBeCollidedWith() {
    return false;
  }

  @Override
  public boolean shouldRenderAtSqrDistance(double distance) {
    return distance < 16384.0D;
  }

  @Override
  protected void readAdditionalSaveData(CompoundTag tag) {}

  @Override
  protected void addAdditionalSaveData(CompoundTag tag) {}
}
