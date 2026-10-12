package com.zfy.zcinema.entity;

import com.zfy.zcinema.registry.ModEntities;
import com.zfy.zcinema.registry.ModItems;
import net.minecraft.core.BlockPos;
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
    /** Five times the 1.5 blocks per tick an egg leaves the hand with. */
    public static final float SPEED = 7.5F;
    /** How far a throw can stray from where it was aimed, in the same units an egg uses 1. Five
     *  times an egg's spread: a spray of emoji rather than a laser. */
    public static final float INACCURACY = 5.0F;
    /**
     * What the emoji is drawn at in flight: two blocks across and two blocks tall. The entity box
     * matches it because Minecraft scales an entity's render distance by 64 times that size.
     */
    public static final float SIZE = 2.0F;
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
        if (!this.level().isClientSide && result instanceof BlockHitResult blockHit) {
            this.splat(blockHit);
        }
        // Both sides stop here: the client should not fly its copy on through the wall while it
        // waits for the server to remove the entity.
        this.discard();
    }

    /**
     * The client keeps its own prediction of the flight instead of snapping to the position updates.
     * Those updates arrive in bursts, and at seven and a half blocks a tick every snap is a visible
     * jump, which is what made the emoji stutter on its way. The prediction runs the same maths the
     * server does, so the only thing given up is following a real teleport - and a throw is never
     * teleported.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        if (this.level().isClientSide) {
            this.setRot(yaw, pitch);
            return;
        }
        super.lerpTo(x, y, z, yaw, pitch, steps);
    }

    /**
     * Leaves the picture two blocks out from the wall that was hit - one block further than the
     * cell a thrown egg would hatch its chicken in, which is what keeps a three block wide picture
     * clear of the wall - and lets it float up from there.
     */
    private void splat(BlockHitResult hit) {
        BlockPos anchor = hit.getBlockPos().relative(hit.getDirection(), 2);
        Vec3 center = new Vec3(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
        this.level().addFreshEntity(new EmojiSplat(this.level(), center, this.getItem()));
        this.level().playSound(null, anchor, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.NEUTRAL, 0.8F, 1.4F);
    }
}
