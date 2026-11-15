package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * One block of the Visuals tab: a switch and the settings under it. A group can show in more than one
 * section (Damage Indicators is under Sword, Axe and UHC); it's the same group, saved once.
 */
public final class Group {
    /** Its key in visuals.json; never changes once released. */
    public final String id;
    public final String name;
    public final String description;
    /** Extra words the search box matches, beyond the name, description and setting names. */
    public final String keywords;
    public final BoolSetting enabled = new BoolSetting("Enabled", false);
    public final List<Setting<?>> settings = new ArrayList<>();
    /** How many sections list it. */
    int sections;

    public Group(String id, String name, String description, String keywords) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.keywords = keywords;
    }

    public <S extends Setting<?>> S add(S setting) {
        settings.add(setting);
        return setting;
    }

    /** Switched on, and nothing is holding visuals off (Stealth). */
    public boolean on() {
        return enabled.get() && Visuals.live();
    }

    public boolean isShared() {
        return sections > 1;
    }
}
