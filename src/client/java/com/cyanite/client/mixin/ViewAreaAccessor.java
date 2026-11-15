package com.cyanite.client.mixin;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ViewArea.class)
public interface ViewAreaAccessor {
    @Invoker("getRenderSectionAt")
    SectionRenderDispatcher.RenderSection cyanite$sectionAt(BlockPos pos);
}
