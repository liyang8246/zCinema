package com.zfy.zcinema.entity;

import com.zfy.zcinema.registry.ModEntities;
import com.zfy.zcinema.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The thrown emoji. Flies like a thrown egg, eight times as fast, and sticks its emoji to the
 * surface it hits.
 */
public class ThrownEmoji extends ThrowableItemProjectile {
    /** Three times the 1.5 blocks per tick an egg leaves the hand with. */
    public static final float SPEED = 4.5F;
    /** At this speed it sails past the view distance in seconds; never keep one forever. */
    private static final int MAX_LIFETIME_TICKS = 60;

    public ThrownEmoji(EntityType<? extends ThrownEmoji> type, Level level) {
        super(type, level);
    }

    public ThrownEmoji(Level level, LivingEntity owner) {
        super(ModEntities.THROWN_EMOJI, owner, level);
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.EMOJIS.get(0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && this.tickCount > MAX_LIFETIME_TICKS) {
            this.discard();
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!this.level().isClientSide) {
            if (result instanceof BlockHitResult blockHit) {
                this.splat(blockHit);
            }
            this.discard();
        }
    }

    /**
     * Hangs the picture on the face that was hit, centred on the block just before it - the same
     * block a thrown egg would hatch its chicken in.
     */
    private void splat(BlockHitResult hit) {
        BlockPos hitPos = hit.getBlockPos();
        Direction face = hit.getDirection();
        BlockPos anchor = hitPos.relative(face);
        double centerX = anchor.getX() + 0.5;
        double centerY = anchor.getY() + 0.5;
        double centerZ = anchor.getZ() + 0.5;
        // Snap the centre onto the surface that was hit, so the picture hangs on the wall instead
        // of floating in the block in front of it.
        Vec3 center = switch (face) {
            case DOWN -> new Vec3(centerX, hitPos.getY(), centerZ);
            case UP -> new Vec3(centerX, hitPos.getY() + 1.0, centerZ);
            case NORTH -> new Vec3(centerX, centerY, hitPos.getZ());
            case SOUTH -> new Vec3(centerX, centerY, hitPos.getZ() + 1.0);
            case WEST -> new Vec3(hitPos.getX(), centerY, centerZ);
            case EAST -> new Vec3(hitPos.getX() + 1.0, centerY, centerZ);
        };
        this.level().addFreshEntity(new EmojiSplat(this.level(), center, face, this.getItem()));
        this.level().playSound(null, anchor, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.NEUTRAL, 0.8F, 1.4F);
    }
}
