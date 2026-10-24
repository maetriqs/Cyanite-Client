package com.cyanite.client.hud;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.Setting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the HUD elements and draws them. Like the click GUI, the HUD uses its own
 * units instead of Minecraft's GUI scale, set by {@link #SCALE}.
 */
public final class Hud {
    public static final List<Setting<?>> SETTINGS = new ArrayList<>();

    public static final ModeSetting SCALE = add(new ModeSetting("Scale", "Auto", List.of("Auto", "1x", "2x", "3x", "4x")));
    public static final BoolSetting SHADOW = add(new BoolSetting("Text shadow", true));
    public static final BoolSetting HIDE_IN_F3 = add(new BoolSetting("Hide with F3", true));

    public static final List<HudElement> ELEMENTS = List.of(new Watermark(), new ModuleList(), new RegionMap(), new StaffPanel(), new PerformancePanel(), new TargetHud(), new ToggleAlerts());

    /** While the click GUI's HUD tab is open it draws the elements itself, so the normal pass skips them. */
    public static boolean editing;

    private static long lastFrame;
    private static int previousScale;

    private Hud() {
    }

    private static <S extends Setting<?>> S add(S setting) {
        SETTINGS.add(setting);
        return setting;
    }

    public static int unit() {
        String scale = SCALE.get();
        if (!scale.equals("Auto")) return scale.charAt(0) - '0';
        return Math.max(1, Math.round(Minecraft.getInstance().getWindow().getHeight() / 540f));
    }

    public static int screenWidth() {
        return Minecraft.getInstance().getWindow().getWidth() / unit();
    }

    public static int screenHeight() {
        return Minecraft.getInstance().getWindow().getHeight() / unit();
    }

    /**
     * Switches the pose into HUD units. Expects the pose to be in Minecraft's GUI space.
     * Pair with {@link #end(GuiGraphics)}.
     */
    public static void begin(GuiGraphics g) {
        Window window = Minecraft.getInstance().getWindow();
        int unit = unit();
        g.pose().pushMatrix();
        g.pose().scale((float) unit / window.getGuiScale());
        previousScale = Render2D.setScale(unit);
    }

    public static void end(GuiGraphics g) {
        Render2D.setScale(previousScale);
        g.pose().popMatrix();
    }

    public static float frameTime() {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9f, 0.1f);
        lastFrame = now;
        return dt;
    }

    /** Fabric HUD callback. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        float dt = frameTime();
        if (mc.options.hideGui || mc.player == null || com.cyanite.client.system.Stealth.active()) return;

        begin(g);
        // Overlays like nametags belong to the world, so they stay even while the HUD is being edited.
        Modules.render2D(g, delta.getGameTimeDeltaPartialTick(true));
        com.cyanite.client.modules.world.build.StandIns.render2D(g);
        if (!editing) Macros.drawHud(g, screenWidth());

        boolean hidden = editing || HIDE_IN_F3.get() && mc.getDebugOverlay().showDebugScreen();
        if (!hidden) {
            int width = screenWidth();
            int height = screenHeight();
            for (HudElement element : ELEMENTS) {
                if (!element.isEnabled()) continue;
                element.update(dt, false);
                element.place(width, height);
                element.draw(g, 1, false);
            }
        }
        end(g);
    }

    public static JsonObject save() {
        JsonObject json = new JsonObject();

        JsonObject general = new JsonObject();
        for (Setting<?> setting : SETTINGS) general.add(setting.name, setting.toJson());
        json.add("settings", general);

        JsonObject elements = new JsonObject();
        for (HudElement element : ELEMENTS) elements.add(element.name, element.save());
        json.add("elements", elements);
        return json;
    }

    public static void load(JsonObject json) {
        if (json.get("settings") instanceof JsonObject general) {
            for (Setting<?> setting : SETTINGS) {
                JsonElement value = general.get(setting.name);
                if (value != null) setting.fromJson(value);
            }
        }
        if (json.get("elements") instanceof JsonObject elements) {
            for (HudElement element : ELEMENTS) {
                if (elements.get(element.name) instanceof JsonObject saved) element.load(saved);
            }
        }
    }
}
