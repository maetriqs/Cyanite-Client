package com.cyanite.client.system;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for every feature in the client. Subclasses declare their category,
 * name and description, add settings as fields, and override whichever hooks they need.
 * Every module also gets a keybind and a show-in-arraylist toggle, listed after its own settings.
 */
public abstract class Module {
    /** Set again when the modules are made, in case something loaded this class before the game existed. */
    protected static Minecraft mc = Minecraft.getInstance();

    public final Category category;
    public final String name;
    public final String description;

    private final List<Setting<?>> own = new ArrayList<>();
    private final KeybindSetting bind = new KeybindSetting("Bind");
    private final BoolSetting visible = new BoolSetting("Show in list", true);
    private List<Setting<?>> all;
    private boolean enabled;
    /** On, but held off while Stealth Mode hides everything; back on when it ends. */
    private boolean held;
    /** Game-thread time spent in this module's hooks since the last reading, and the last second's share of it (%). */
    long spentNanos;
    float load;

    static void bindGame(Minecraft minecraft) {
        mc = minecraft;
    }

    protected Module(Category category, String name, String description) {
        this.category = category;
        this.name = name;
        this.description = description;
    }

    protected <S extends Setting<?>> S add(S setting) {
        own.add(setting);
        all = null;
        return setting;
    }

    public List<Setting<?>> settings() {
        if (all == null) {
            List<Setting<?>> list = new ArrayList<>(own);
            list.add(bind);
            list.add(visible);
            all = List.copyOf(list);
        }
        return all;
    }

    public KeybindSetting bind() {
        return bind;
    }

    public boolean isVisible() {
        return visible.get();
    }

    /** Short status shown next to the name in the arraylist, e.g. a mode. Null for none. */
    public String getInfo() {
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** How much of the game thread's time this module took over the last second, in percent. */
    public float cost() {
        return load;
    }

    /** On, or would be if Stealth Mode weren't holding it off. This is what gets saved. */
    public boolean isWanted() {
        return enabled || held;
    }

    public void toggle() {
        setEnabled(!isWanted());
    }

    public void setEnabled(boolean enabled) {
        if (!enabled) {
            held = false;
        } else if (Stealth.active() && hiddenInStealth()) {
            // Switched on while hidden (a keybind, a profile): it waits until Stealth Mode ends.
            held = true;
            return;
        }
        if (this.enabled == enabled) return;

        this.enabled = enabled;

        if (enabled) onEnable();
        else onDisable();
        com.cyanite.client.hud.ToggleAlerts.onToggle(this, enabled);
    }

    /** Stealth Mode: off for now, and back on when it ends. */
    public void holdForStealth() {
        if (!enabled) return;
        setEnabled(false);
        held = true;
    }

    /** Stealth Mode's over: back on if it was held. */
    public void releaseFromStealth() {
        if (!held) return;
        held = false;
        setEnabled(true);
    }

    /** Whether Stealth Mode switches this off: everything that changes what you see, and anything else that shows the client. */
    public boolean hiddenInStealth() {
        return category == Category.RENDER;
    }

    /** True when there's a player and world to act on. */
    protected static boolean inGame() {
        return mc.player != null && mc.level != null;
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    /** Runs at the end of every client tick while enabled and in a world. */
    public void onTick() {
    }

    /** Runs once per frame, right after the mouse has turned the player, while enabled and in a world. */
    public void onFrame() {
    }

    /** Queue world-space shapes for this frame. Runs every frame while enabled and in a world. */
    public void onRender3D(Render3D render) {
    }

    /**
     * True for modules any anticheat catches: only for while the server's anticheat is down. The
     * menu and the module list show them in red, so one isn't left on by accident.
     */
    public boolean anticheatDownOnly() {
        return false;
    }

    /** A key pressed in game (no screen open), for modules with keys of their own. */
    public void onKey(int key) {
    }

    /** True to have {@link #onRender3D} called even while switched off, for previews. */
    public boolean rendersWhileDisabled() {
        return false;
    }

    /** Draw screen-space overlays in HUD units. Runs every frame while enabled and in a world. */
    public void onRender2D(GuiGraphics g, float partialTick) {
    }
}
