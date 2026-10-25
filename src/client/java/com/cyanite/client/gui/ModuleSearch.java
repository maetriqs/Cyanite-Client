package com.cyanite.client.gui;

import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.Setting;

import java.util.Locale;
import java.util.Set;

/** How well a module matches what's typed in a search box. Both menus search the same way. */
final class ModuleSearch {
    /** Settings every module has; a search for "bind" shouldn't list them all. */
    private static final Set<String> COMMON = Set.of("Bind", "Show in list");

    /** Best first: the name starts with it. */
    static final int STARTS = 0;
    /** A word of the name starts with it, or it's the initials ("ka" for Kill Aura). */
    static final int WORD = 1;
    /** It's somewhere in the name, mid-word ("esp" in Auto Respawn). */
    static final int INSIDE = 2;
    /** A setting of the module is called it. */
    static final int SETTING = 3;
    /** The module's description mentions it. */
    static final int DESCRIPTION = 4;

    /** A match: how good it is, and the setting that matched when that's why. */
    record Hit(int score, Setting<?> setting) {
    }

    private ModuleSearch() {
    }

    /** {@code q} is already stripped and lower case. Null when the module doesn't match at all. */
    static Hit match(Module module, String q) {
        String name = module.name.toLowerCase(Locale.ROOT);
        if (name.startsWith(q)) return new Hit(STARTS, null);
        if (wordStarts(name, q) || initials(name).startsWith(q.replace(" ", ""))) return new Hit(WORD, null);
        if (name.contains(q)) return new Hit(INSIDE, null);
        for (Setting<?> setting : module.settings()) {
            if (!COMMON.contains(setting.name) && setting.name.toLowerCase(Locale.ROOT).contains(q)) return new Hit(SETTING, setting);
        }
        if (module.description.toLowerCase(Locale.ROOT).contains(q)) return new Hit(DESCRIPTION, null);
        return null;
    }

    /** Whether a word of {@code name} (after a space, & or the start) begins with {@code q}. */
    static boolean wordStarts(String name, String q) {
        for (int i = name.indexOf(q); i >= 0; i = name.indexOf(q, i + 1)) {
            if (i == 0 || name.charAt(i - 1) == ' ' || name.charAt(i - 1) == '&') return true;
        }
        return false;
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String word : name.split("[\\s&]+")) {
            if (!word.isEmpty()) sb.append(word.charAt(0));
        }
        return sb.toString();
    }
}
