package com.cyanite.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("fogRenderer")
    FogRenderer cyanite$fogRenderer();

    @Accessor("resourcePool")
    com.mojang.blaze3d.resource.CrossFrameResourcePool cyanite$resourcePool();
}
