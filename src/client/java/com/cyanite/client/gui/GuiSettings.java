package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.Setting;
import com.cyanite.client.system.setting.SliderSetting;

import java.util.ArrayList;
import java.util.List;

public final class GuiSettings {
    public static final List<Setting<?>> ALL = new ArrayList<>();

    public static final ModeSetting FONT = add(new ModeSetting("Font", Text.MINECRAFT, Text.choices()));
    public static final ModeSetting SCALE = add(new ModeSetting("UI scale", "Auto", List.of("Auto", "1x", "2x", "3x", "4x")));
    public static final ColorSetting ACCENT = add(new ColorSetting("Accent", 0x4C9BFF));
    public static final SliderSetting GLOW = add(new SliderSetting("Glow", 1, 0, 2, 0.05).percent());
    public static final SliderSetting RADIUS = add(new SliderSetting("Corner radius", 3.5, 0, 6, 0.5));
    public static final BoolSetting BLUR = add(new BoolSetting("Background blur", true));
    public static final BoolSetting ANIMATIONS = add(new BoolSetting("Animations", true));
    /** The single-window menu with search, instead of the floating category windows. */
    public static final BoolSetting BETA = add(new BoolSetting("Beta UI", false));
    /** The title, loading and pause screens in Cyanite's look; off leaves them to the game and other mods. */
    public static final BoolSetting HOME = add(new BoolSetting("Cyanite title screen", true));
    /** Behind the title screen and menus: Cyanite's own, plainer, the game's panorama, or your picture (cyanite/background.png or .jpg). */
    public static final ModeSetting BACKGROUND = add(new ModeSetting("Title background", "Crystal", List.of("Crystal", "Minimal", "Panorama", "Picture")));
    /** After a dropped connection, a restart or a full server, the disconnected screen counts down and puts you back on (three tries). */
    public static final BoolSetting REJOIN = add(new BoolSetting("Rejoin countdown", true));
    /** New releases from GitHub: put in when the game closes, just mentioned, or not looked for. */
    public static final ModeSetting UPDATES = add(new ModeSetting("Updates", "Install", List.of("Install", "Tell me", "Off")));
    /** Counts you on Cyanite's online counter (once Mojang vouches for your account) and shows how many are on; off sends nothing. */
    public static final BoolSetting ONLINE = add(new BoolSetting("Online count", true));

    static {
        FONT.onChange(() -> Text.setFamily(FONT.get()));
        BACKGROUND.onChange(Backdrop::picked);
    }

    private GuiSettings() {
    }

    private static <S extends Setting<?>> S add(S setting) {
        ALL.add(setting);
        return setting;
    }
}
