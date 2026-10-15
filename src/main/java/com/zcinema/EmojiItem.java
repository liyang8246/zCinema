package com.zcinema;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class EmojiItem extends Item {
    public static final String[] EMOJI_IDS = {
            "emoji_joy", "emoji_rofl", "emoji_sob", "emoji_heart_eyes",
            "emoji_thinking", "emoji_skull", "emoji_fire", "emoji_thumbsup",
            "emoji_thumbsdown", "emoji_clap", "emoji_eyes", "emoji_heart",
            "emoji_broken_heart", "emoji_100", "emoji_party", "emoji_scream",
            "emoji_rage", "emoji_clown", "emoji_poop", "emoji_sunglasses",
            "emoji_sleeping", "emoji_vomiting", "emoji_exploding_head", "emoji_pleading",
            "emoji_sweat_smile", "emoji_smirk", "emoji_rolling_eyes", "emoji_flushed",
            "emoji_salute", "emoji_pray", "emoji_popcorn", "emoji_question"
    };

    public final int emojiIndex;

    public EmojiItem(int emojiIndex, Properties properties) {
        super(properties);
        this.emojiIndex = emojiIndex;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        level.playSound(
                null,
                player.getX(), player.getY(), player.getZ(),
                SoundEvents.EGG_THROW,
                SoundSource.PLAYERS,
                0.5F,
                0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F)
        );
        if (!level.isClientSide) {
            EmojiEntity entity = new EmojiEntity(level, player, this.emojiIndex);
            level.addFreshEntity(entity);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
