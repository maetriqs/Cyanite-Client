package com.cyanite.client.modules.donut;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.StringSetting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the sidebar scoreboard on your own screen. Nothing is sent anywhere: the server
 * still has the real numbers, and everyone else still sees them.
 *
 * <p>GuiMixin feeds every line through here twice per frame, once while the sidebar measures
 * itself and once while it draws, so the background box fits the new text.
 */
public class ScoreboardSpoof extends Module {
    /** How many sidebar lines can be overridden, counted from the top. */
    private static final int LINES = 8;

    private final ModeSetting lineMode = add(new ModeSetting("Line mode", "Value only", List.of("Value only", "Whole line")));
    private final StringSetting title = add(new StringSetting("Title", "").placeholder("unchanged"));
    private final List<StringSetting> lines = new ArrayList<>();
    private final BoolSetting hideNumbers = new BoolSetting("Hide numbers", false);

    private int measured;
    private int drawn;

    public ScoreboardSpoof() {
        super(Category.DONUT, "Scoreboard Spoof", "Changes the sidebar on your screen only");
        for (int i = 1; i <= LINES; i++) {
            lines.add(add(new StringSetting("Line " + i, "").placeholder("unchanged")));
        }
        add(hideNumbers);
    }

    @Override
    public String getInfo() {
        int count = title.isBlank() ? 0 : 1;
        for (StringSetting line : lines) {
            if (!line.isBlank()) count++;
        }
        return Integer.toString(count);
    }

    /** Start of a sidebar frame: the measure and draw passes each walk the lines top to bottom. */
    public void beginSidebar() {
        measured = 0;
        drawn = 0;
    }

    public Component spoofTitle(Component original) {
        // A title is a name, not a stat, so it always replaces whole.
        return !isEnabled() || title.isBlank() ? original : SidebarText.rewrite(original, title.get(), true);
    }

    public Component spoofMeasuredLine(Component original) {
        return line(original, measured++);
    }

    public Component spoofDrawnLine(Component original) {
        return line(original, drawn++);
    }

    public boolean hidesNumbers() {
        return isEnabled() && hideNumbers.get();
    }

    private Component line(Component original, int index) {
        if (!isEnabled() || index >= lines.size()) return original;
        String custom = lines.get(index).get();
        return custom.isBlank() ? original : SidebarText.rewrite(original, custom, lineMode.is("Whole line"));
    }
}
