package com.cyanite.client.modules.render;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Culling;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Hides every block except the chosen ores. BlockStateBaseMixin asks {@link #hides} when
 * chunks are built, which works the same under vanilla rendering and Sodium.
 */
public class Xray extends Module {
    // Read from chunk-building threads, so it's swapped as a whole rather than edited.
    private static volatile Set<Block> visible;

    private final Map<Ore, BoolSetting> ores = new EnumMap<>(Ore.class);
    private final BoolSetting fullbright = add(new BoolSetting("Fullbright", true));

    private boolean dirty;

    public Xray() {
        super(Category.RENDER, "Xray", "Shows ores through everything else");
        for (Ore ore : Ore.values()) {
            BoolSetting setting = add(new BoolSetting(ore.title, true));
            setting.onChange(() -> dirty = true);
            ores.put(ore, setting);
        }
    }

    /** True if Xray is on and this block should be left out of chunk meshes. */
    public static boolean hides(BlockState state) {
        Set<Block> shown = visible;
        return shown != null && !shown.contains(state.getBlock());
    }

    public boolean brightens() {
        return isEnabled() && fullbright.get();
    }

    @Override
    public String getInfo() {
        int count = 0;
        for (BoolSetting setting : ores.values()) {
            if (setting.get()) count++;
        }
        return Integer.toString(count);
    }

    @Override
    protected void onEnable() {
        // Sections hidden behind solid rock get skipped by occlusion culling, ores and all.
        Culling.hold(this);
        apply();
    }

    @Override
    protected void onDisable() {
        visible = null;
        Culling.release(this);
        rebuildChunks();
    }

    @Override
    public void onTick() {
        if (!dirty) return;
        dirty = false;
        apply();
    }

    private void apply() {
        Set<Block> shown = Collections.newSetFromMap(new IdentityHashMap<>());
        ores.forEach((ore, setting) -> {
            if (setting.get()) shown.addAll(ore.blocks);
        });
        visible = Collections.unmodifiableSet(shown);
        rebuildChunks();
    }

    private static void rebuildChunks() {
        if (inGame()) mc.levelRenderer.allChanged();
    }
}
