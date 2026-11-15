package com.cyanite.client.visuals;

import com.cyanite.client.mixin.visuals.ItemStackRenderStateAccessor;
import com.cyanite.client.mixin.visuals.LayerRenderStateAccessor;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ItemListSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Items you pick drawn in a colour of yours: in your hand, on the ground, in frames and in every menu.
 * Three lists, each with its own colour. The colour is laid over the item's texture, so its shading stays.
 */
public final class ItemColors {
    public static final Group GROUP = new Group("item_colors", "Item Colors",
        "Pick items and give them a colour of yours, everywhere they're drawn.", "item colour color tint recolor rgb picker");
    static final ItemListSetting ITEMS_1 = GROUP.add(new ItemListSetting("Items (1)", Items.TOTEM_OF_UNDYING));
    static final ColorSetting COLOR_1 = GROUP.add(new ColorSetting("Color (1)", 0xFFD54A).full());
    static final ItemListSetting ITEMS_2 = GROUP.add(new ItemListSetting("Items (2)", Items.END_CRYSTAL));
    static final ColorSetting COLOR_2 = GROUP.add(new ColorSetting("Color (2)", 0xC56BFF).full());
    static final ItemListSetting ITEMS_3 = GROUP.add(new ItemListSetting("Items (3)", Items.NETHERITE_SWORD));
    static final ColorSetting COLOR_3 = GROUP.add(new ColorSetting("Color (3)", 0x4DE1FF).full());
    static final SliderSetting STRENGTH = GROUP.add(new SliderSetting("Strength", 0.7, 0.1, 1, 0.05).percent());

    /** The tint slot untinted quads are moved to; past any the game's own models use. */
    private static final int SLOT = 15;
    /** Untinted quads, each with a copy that takes {@link #SLOT}'s colour. Cleared if it grows past every item's worth. */
    private static final Map<BakedQuad, BakedQuad> RETINTED = new IdentityHashMap<>();

    private ItemColors() {
    }

    /** The ARGB tint for {@code item}, or 0 to leave it. */
    public static int colorFor(Item item) {
        if (!GROUP.on()) return 0;
        int rgb;
        if (ITEMS_1.contains(item)) rgb = COLOR_1.get();
        else if (ITEMS_2.contains(item)) rgb = COLOR_2.get();
        else if (ITEMS_3.contains(item)) rgb = COLOR_3.get();
        else return 0;
        return Visuals.lerp(0xFFFFFFFF, 0xFF000000 | rgb, STRENGTH.get().floatValue());
    }

    /** After an item's layers were added to {@code state} (from layer {@code from} on): tint them. */
    public static void apply(ItemStackRenderState state, int from, ItemStack stack) {
        if (stack.isEmpty()) return;
        int tint = colorFor(stack.getItem());
        if (tint == 0) return;
        ItemStackRenderStateAccessor s = (ItemStackRenderStateAccessor) state;
        ItemStackRenderState.LayerRenderState[] layers = s.cyanite$layers();
        int count = s.cyanite$activeLayerCount();
        boolean any = false;
        for (int i = from; i < count; i++) {
            LayerRenderStateAccessor layer = (LayerRenderStateAccessor) layers[i];
            if (layer.cyanite$specialRenderer() != null) continue;
            List<BakedQuad> quads = layer.cyanite$quads();
            if (quads.isEmpty()) continue;
            // Quads with a tint slot already (leather, potions, grass) keep it, times our colour; the rest move to ours.
            int[] old = layer.cyanite$tintLayers();
            int[] tints = new int[Math.max(old.length, SLOT + 1)];
            for (int t = 0; t < tints.length; t++) {
                int was = t < old.length ? old[t] : -1;
                tints[t] = Visuals.multiply(was, tint);
            }
            tints[SLOT] = tint;
            for (int q = 0; q < quads.size(); q++) {
                BakedQuad quad = quads.get(q);
                if (!quad.isTinted()) quads.set(q, retinted(quad));
            }
            layer.cyanite$setTintLayers(tints);
            any = true;
        }
        // The menus draw each item once into an atlas and reuse it: a new colour has to look like a new item.
        if (any) state.appendModelIdentityElement(tint);
    }

    private static BakedQuad retinted(BakedQuad quad) {
        BakedQuad copy = RETINTED.get(quad);
        if (copy == null) {
            if (RETINTED.size() > 60_000) RETINTED.clear();
            copy = new BakedQuad(quad.position0(), quad.position1(), quad.position2(), quad.position3(),
                quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
                SLOT, quad.direction(), quad.sprite(), quad.shade(), quad.lightEmission());
            RETINTED.put(quad, copy);
        }
        return copy;
    }
}
