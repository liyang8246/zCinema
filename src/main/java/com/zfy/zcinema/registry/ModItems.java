package com.zfy.zcinema.registry;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.item.EmojiItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The throwable emoji, one item per emoji, plus the creative tab that holds them. Names are the
 * emoji's short names, so {@code zcinema:emoji_fire} is the item and
 * {@code assets/zcinema/textures/item/emoji_fire.png} its Apple artwork.
 *
 * <p>Artwork: Apple's emoji images, taken from <a href="https://github.com/iamcal/emoji-data">
 * iamcal/emoji-data</a> (CC-BY-4.0 database, images &copy; Apple Inc.).
 */
public final class ModItems {
    /**
     * Every throwable emoji, in tab order: joy, heart, rofl, thumbsup, sob, pray, kissing_heart,
     * smiling_face_with_3_hearts, heart_eyes, blush, tada, grin, two_hearts, pleading_face,
     * sweat_smile, thinking, hugs, wink, roll_eyes, sunglasses, sparkles, skull,
     * smiling_face_with_tear, smirk, cry, weary, star_struck, rage, yum, clap, fire, hundred.
     */
    private static final String[] NAMES = {
        "joy",
        "heart",
        "rofl",
        "thumbsup",
        "sob",
        "pray",
        "kissing_heart",
        "smiling_face_with_3_hearts",
        "heart_eyes",
        "blush",
        "tada",
        "grin",
        "two_hearts",
        "pleading_face",
        "sweat_smile",
        "thinking",
        "hugs",
        "wink",
        "roll_eyes",
        "sunglasses",
        "sparkles",
        "skull",
        "smiling_face_with_tear",
        "smirk",
        "cry",
        "weary",
        "star_struck",
        "rage",
        "yum",
        "clap",
        "fire",
        "hundred",
    };

    public static final List<Item> EMOJIS = new ArrayList<>();

    public static final ResourceKey<CreativeModeTab> EMOJI_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, ZCinema.id("emoji"));

    private ModItems() {}

    /** Loading this holder class is what performs the registration. */
    public static void register() {
        for (String name : NAMES) {
            ResourceLocation sprite = ZCinema.id("textures/item/emoji_" + name + ".png");
            Item item = new EmojiItem(new Item.Properties(), sprite);
            Registry.register(BuiltInRegistries.ITEM, ZCinema.id("emoji_" + name), item);
            EMOJIS.add(item);
        }

        Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB,
                EMOJI_TAB,
                FabricItemGroup.builder()
                        .title(Component.translatable("itemGroup.zcinema.emoji"))
                        .icon(() -> new ItemStack(EMOJIS.get(0)))
                        .displayItems((parameters, output) -> EMOJIS.forEach(output::accept))
                        .build());
    }
}
