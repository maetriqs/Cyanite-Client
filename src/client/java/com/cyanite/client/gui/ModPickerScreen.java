package com.cyanite.client.gui;

import com.cyanite.client.modules.misc.AntiModCheck;
import com.cyanite.client.system.setting.ModListSetting;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Picks the mods Anti Mod Check lets through: every installed mod with text a server could ask
 * about, off (hidden, the default) or on (the server can see it). Mods a server has asked about
 * this session say so.
 */
public class ModPickerScreen extends PickerScreen<AntiModCheck.ModInfo> {
    private final ModListSetting setting;

    public ModPickerScreen(ModListSetting setting, Screen parent) {
        super("Mods servers can see", parent);
        this.setting = setting;
    }

    @Override
    protected List<AntiModCheck.ModInfo> entries() {
        return AntiModCheck.mods();
    }

    @Override
    protected String name(AntiModCheck.ModInfo e) {
        return e.name();
    }

    @Override
    protected String searchText(AntiModCheck.ModInfo e) {
        return e.id();
    }

    @Override
    protected boolean isOn(AntiModCheck.ModInfo e) {
        return setting.contains(e.id());
    }

    @Override
    protected void toggle(AntiModCheck.ModInfo e) {
        setting.toggle(e.id());
    }

    @Override
    protected int onCount() {
        return setting.get().size();
    }

    @Override
    protected String countText(int count) {
        return count == 0 ? "All hidden" : count + " let through";
    }

    @Override
    protected void clearAll() {
        setting.clear();
    }

    @Override
    protected String clearText() {
        return "Hide all";
    }

    @Override
    protected String placeholder() {
        return "Search mods: cyanite, meteor, voice...";
    }

    @Override
    protected String emptyText() {
        return "No mod called that";
    }

    @Override
    protected String hint() {
        return "On: servers can see it   Off: hidden   Esc goes back";
    }

    @Override
    protected String onHeading() {
        return "Let through";
    }

    @Override
    protected String restHeading() {
        return "Hidden";
    }

    @Override
    protected String note(AntiModCheck.ModInfo e) {
        return AntiModCheck.askedAbout(e.id()) ? "a server asked" : null;
    }
}
