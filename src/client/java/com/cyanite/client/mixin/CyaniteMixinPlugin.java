package com.cyanite.client.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Leaves out the mixins for vanilla's chunk renderer when Sodium has replaced it. */
public class CyaniteMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> VANILLA_TERRAIN_ONLY = Set.of(
        "com.cyanite.client.mixin.VanillaTerrainMixin",
        "com.cyanite.client.mixin.SectionOcclusionGraphMixin");

    private boolean sodium;

    @Override
    public void onLoad(String mixinPackage) {
        sodium = FabricLoader.getInstance().isModLoaded("sodium");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !(sodium && VANILLA_TERRAIN_ONLY.contains(mixinClassName));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
