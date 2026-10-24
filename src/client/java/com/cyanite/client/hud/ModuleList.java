package com.cyanite.client.hud;

import com.cyanite.client.gui.Anim;
import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** The arraylist: every enabled module, sliding in and out as they toggle. */
public class ModuleList extends HudElement {
    private static final int LINE = 11;
    private static final int PAD = 4;
    private static final String EMPTY = "No active modules";

    private final ModeSetting sort = add(new ModeSetting("Sort", "Length", List.of("Length", "Alphabetical")));
    private final ModeSetting color = add(new ModeSetting("Color", "Accent", List.of("Accent", "Wave", "Rainbow", "White")));
    private final BoolSetting info = add(new BoolSetting("Module info", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    private final BoolSetting bar = add(new BoolSetting("Side bar", true));
    private final BoolSetting glow = add(new BoolSetting("Glow", true));

    private final Map<Module, Line> lines = new IdentityHashMap<>();
    private final List<Line> visible = new ArrayList<>();
    private boolean placeholder;

    private static final class Line {
        final Module module;
        float slide;
        String info;
        int textWidth;

        Line(Module module) {
            this.module = module;
        }
    }

    public ModuleList() {
        super("Arraylist", true, Horizontal.RIGHT, Vertical.TOP, 2, 2);
    }

    @Override
    public void update(float dt, boolean editing) {
        visible.clear();

        for (Module module : Modules.getAll()) {
            Line line = lines.computeIfAbsent(module, Line::new);
            line.slide = Anim.approach(line.slide, module.isEnabled() && module.isVisible() ? 1 : 0, dt, 12);
            if (line.slide < 0.005f) continue;

            line.info = info.get() ? module.getInfo() : null;
            line.textWidth = Text.width(module.name) + (line.info != null ? 4 + Text.width(line.info) : 0);
            visible.add(line);
        }

        if (sort.is("Length")) {
            visible.sort(Comparator.comparingInt((Line l) -> l.textWidth).reversed().thenComparing(l -> l.module.name));
        } else {
            visible.sort(Comparator.comparing(l -> l.module.name, String.CASE_INSENSITIVE_ORDER));
        }

        placeholder = visible.isEmpty() && editing;
        if (placeholder) {
            width = Text.width(EMPTY) + PAD * 2;
            height = LINE;
            return;
        }

        int widest = 0;
        float total = 0;
        for (Line line : visible) {
            widest = Math.max(widest, line.textWidth);
            total += LINE * Anim.easeOut(line.slide);
        }
        width = widest + PAD * 2 + barWidth();
        height = Math.max(1, Math.round(total));
    }

    private int barWidth() {
        return bar.get() ? 2 : 0;
    }

    @Override
    public void draw(GuiGraphics g, float alpha, boolean editing) {
        if (placeholder) {
            Text.draw(g, EMPTY, x + PAD, y + (LINE - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, alpha), Hud.SHADOW.get());
            return;
        }

        boolean right = horizontal != Horizontal.LEFT;
        boolean upwards = vertical == Vertical.BOTTOM;
        int s = Render2D.scale();
        float time = (System.nanoTime() % 1_000_000_000_000L) / 1e9f;
        float cursor = upwards ? y + height : y;

        for (int i = 0; i < visible.size(); i++) {
            Line line = visible.get(i);
            float shown = Anim.easeOut(line.slide);
            float a = alpha * shown;
            float lineH = LINE * shown;

            if (upwards) cursor -= lineH;
            int ly = Math.round(cursor);
            if (!upwards) cursor += lineH;

            int lw = line.textWidth + PAD * 2 + barWidth();
            int slide = Math.round((1 - shown) * (lw + 6));
            int lx = right ? x + width - lw + slide : x - slide;
            // A red reminder while an anticheat-down-only module is on.
            int textColor = line.module.anticheatDownOnly() ? Theme.DANGER : colorFor(i, time);

            Render2D.pixels(g);
            if (background.get()) {
                Render2D.rect(g, lx * s, ly * s, lw * s, LINE * s, 0, Theme.fade(0xC00D0F13, a));
            }
            if (bar.get()) {
                int bw = Math.max(2, Math.round(1.5f * s));
                int bx = right ? (lx + lw) * s - bw : lx * s;
                if (glow.get()) Render2D.glow(g, bx, ly * s, bw, LINE * s, 0, 5 * s, Theme.alpha(textColor, 130 * Theme.glow() * a));
                Render2D.rect(g, bx, ly * s, bw, LINE * s, 0, Theme.fade(textColor, a));
            }
            int textX = lx + PAD + (right ? 0 : barWidth());
            int textY = ly + (LINE - Text.CAP) / 2;
            if (glow.get()) {
                Render2D.glow(g, textX * s, textY * s, Text.width(line.module.name) * s, Text.CAP * s, 2 * s, 6 * s,
                    Theme.alpha(textColor, 28 * Theme.glow() * a));
            }
            Render2D.end(g);

            boolean shadow = Hud.SHADOW.get();
            Text.draw(g, line.module.name, textX, textY, Theme.fade(textColor, a), shadow);
            if (line.info != null) {
                Text.draw(g, line.info, textX + Text.width(line.module.name) + 4, textY, Theme.fade(Theme.TEXT_DIM, a), shadow);
            }
        }
    }

    private int colorFor(int index, float time) {
        return switch (color.get()) {
            case "Wave" -> {
                float t = 0.5f + 0.5f * (float) Math.sin(time * 2.6f - index * 0.45f);
                yield Theme.mix(Theme.accent(), Theme.accentText(), t);
            }
            case "Rainbow" -> 0xFF000000 | ColorSetting.fromHsv(time * 0.12f + index * 0.045f, 0.55f, 1f);
            case "White" -> Theme.TEXT;
            default -> Theme.accentText();
        };
    }
}
