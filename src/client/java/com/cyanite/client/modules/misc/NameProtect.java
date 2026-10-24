package com.cyanite.client.modules.misc;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.StringSetting;
import net.minecraft.client.Minecraft;

/**
 * Shows a name of your choosing wherever the game would draw your real one: chat, the tab list,
 * the scoreboard, your nametag. Only on your screen; everyone else still sees your real name.
 * StringDecomposerMixin swaps it in just before text is drawn or measured.
 */
public class NameProtect extends Module {
    private final StringSetting name = add(new StringSetting("Name", "Player", 16).placeholder("shown instead"));

    public NameProtect() {
        super(Category.MISC, "Name Protect", "Hides your real name on your screen");
    }

    /** {@code text} with your real name replaced, or {@code text} itself when there's nothing to do. */
    public static String protect(String text) {
        NameProtect protect = Modules.get(NameProtect.class);
        if (protect == null || !protect.isEnabled() || text == null || text.isEmpty()) return text;
        String real = Minecraft.getInstance().getUser().getName();
        String fake = protect.name.get();
        if (real.isEmpty() || fake.isBlank() || !text.contains(real)) return text;
        return text.replace(real, fake);
    }
}
