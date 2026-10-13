package com.zfy.zcinema.item;

import com.zfy.zcinema.entity.ThrownEmoji;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * An emoji you can throw: right click sends it flying like an egg, except eight times as fast, and
 * wherever it lands it leaves the emoji behind as a picture three blocks wide.
 */
public class EmojiItem extends Item {
    /** The emoji png. Thrown emoji and the picture they leave behind both draw it. */
    private final ResourceLocation sprite;

    public EmojiItem(Properties properties, ResourceLocation sprite) {
        super(properties);
        this.sprite = sprite;
    }

    public ResourceLocation sprite() {
        return sprite;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        level.playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                SoundEvents.EGG_THROW,
                SoundSource.PLAYERS,
                0.5F,
                0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
        if (!level.isClientSide) {
            ThrownEmoji thrown = new ThrownEmoji(level, player);
            thrown.setItem(stack);
            thrown.shootFromRotation(
                    player,
                    player.getXRot(),
                    player.getYRot(),
                    0.0F,
                    ThrownEmoji.SPEED,
                    ThrownEmoji.INACCURACY);
            level.addFreshEntity(thrown);
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        stack.consume(1, player);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
