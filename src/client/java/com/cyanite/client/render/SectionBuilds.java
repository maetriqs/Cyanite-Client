package com.cyanite.client.render;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Whether the chunk section holding a block is drawn as its blocks are now: built, showing, and with
 * no rebuild waiting or under way. Vanilla and Sodium both count a section built once as ready even
 * after its blocks change (as when the server swaps the blocks it faked for real ones), so a picture
 * taken then would show the old blocks.
 */
public final class SectionBuilds {
    private static final Logger LOGGER = LoggerFactory.getLogger("Cyanite");
    private static final Minecraft mc = Minecraft.getInstance();

    /** Vanilla's chunk renderer (VanillaTerrainMixin). */
    public interface Vanilla {
        boolean cyanite$sectionCurrent(BlockPos pos);
    }

    /** One of vanilla's chunk sections (RenderSectionMixin). */
    public interface Building {
        boolean cyanite$building();
    }

    private static boolean sodiumLooked;
    private static Method sodiumRenderer;
    private static Field sodiumManager;
    private static Field sodiumSections;
    private static Method sodiumPending;
    private static Method sodiumJob;

    private SectionBuilds() {
    }

    public static boolean current(BlockPos pos) {
        if (!mc.levelRenderer.isSectionCompiledAndVisible(pos)) return false;
        if (mc.levelRenderer instanceof Vanilla vanilla) return vanilla.cyanite$sectionCurrent(pos);
        return sodiumCurrent(pos);
    }

    /** Sodium keeps its sections to itself, so they're looked at by reflection. True if that fails. */
    private static boolean sodiumCurrent(BlockPos pos) {
        if (!sodiumLooked) lookAtSodium();
        if (sodiumRenderer == null) return true;
        try {
            Object renderer = sodiumRenderer.invoke(null);
            if (renderer == null) return true;
            Object sections = sodiumSections.get(sodiumManager.get(renderer));
            Object section = ((Long2ReferenceMap<?>) sections).get(SectionPos.asLong(pos));
            return section != null && (int) sodiumPending.invoke(section) == 0 && sodiumJob.invoke(section) == null;
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOGGER.warn("Couldn't ask Sodium whether a chunk section is up to date", e);
            sodiumRenderer = null;
            return true;
        }
    }

    private static void lookAtSodium() {
        sodiumLooked = true;
        if (!FabricLoader.getInstance().isModLoaded("sodium")) return;
        try {
            Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager");
            Class<?> section = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSection");
            Field managerField = renderer.getDeclaredField("renderSectionManager");
            Field sectionsField = manager.getDeclaredField("sectionByPosition");
            managerField.setAccessible(true);
            sectionsField.setAccessible(true);
            sodiumManager = managerField;
            sodiumSections = sectionsField;
            sodiumPending = section.getMethod("getPendingUpdate");
            sodiumJob = section.getMethod("getRunningJob");
            sodiumRenderer = renderer.getMethod("instanceNullable");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("Couldn't find Sodium's chunk sections: pictures may show blocks as they were", e);
        }
    }
}
