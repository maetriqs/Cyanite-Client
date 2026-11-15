package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.ItemColors;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

// Item Colors: the layers an item just added get its colour, before anything draws them (held, dropped, in menus).
@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {
    @WrapMethod(method = "appendItemLayers")
    private void cyanite$itemColors(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, Level level, ItemOwner owner, int seed,
                                    Operation<Void> original) {
        int from = ((ItemStackRenderStateAccessor) state).cyanite$activeLayerCount();
        original.call(state, stack, context, level, owner, seed);
        ItemColors.apply(state, from, stack);
    }
}
