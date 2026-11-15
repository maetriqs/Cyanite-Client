package com.cyanite.client.mixin.visuals;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Custom Hit Colors: the texture whose top rows are the hurt colour.
@Mixin(OverlayTexture.class)
public interface OverlayTextureAccessor {
    @Accessor("texture")
    DynamicTexture cyanite$texture();
}
