package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Text;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphics;

final class GuiPage extends Page {
    private static final int W = 196;
    private static final int HEADER = 18;
    private static final int PAD = 4;

    private final SettingsList list = new SettingsList(GuiSettings.ALL);
    private float resetHover;
    private float tutorialHover;
    /** Set by the screen; replays the tour. */
    Runnable onTutorial = () -> {
    };
    private int x;
    private int y;

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        float a = alpha * entrance();
        x = (width - W) / 2;
        y = TOP + Math.round((1 - entrance()) * -6);

        int footer = 14;
        int body = PAD + list.height() + footer;
        boolean hovered = inside(mouseX, mouseY, x, y, W, HEADER + body);

        Panel.draw(g, x, y, W, HEADER, body, a, hovered ? 0.6f : 0.35f, 0, 1);
        bounds = new Rect(x, y, W, HEADER + body);
        Text.draw(g, "Interface", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));

        String reset = "Reset";
        int resetX = x + W - 8 - Text.width(reset);
        boolean overReset = inside(mouseX, mouseY, resetX - 3, y, Text.width(reset) + 6, HEADER);
        resetHover = Anim.approach(resetHover, overReset ? 1 : 0, dt, 16);
        if (overReset) g.requestCursor(CursorTypes.POINTING_HAND);
        Text.draw(g, reset, resetX, y + (HEADER - Text.CAP) / 2,
            Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.accentText(), resetHover), a));

        String tour = "Tutorial";
        int tourX = resetX - 12 - Text.width(tour);
        boolean overTour = inside(mouseX, mouseY, tourX - 3, y, Text.width(tour) + 6, HEADER);
        tutorialHover = Anim.approach(tutorialHover, overTour ? 1 : 0, dt, 16);
        if (overTour) g.requestCursor(CursorTypes.POINTING_HAND);
        Text.draw(g, tour, tourX, y + (HEADER - Text.CAP) / 2,
            Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.accentText(), tutorialHover), a));

        list.render(g, x + 4, y + HEADER + PAD, W - 8, mouseX, mouseY, dt, a, hovered);

        Text.draw(g, "Right click a value to reset it", x + 8, y + HEADER + body - footer + 2,
            Theme.fade(Theme.TEXT_FAINT, a));
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        String reset = "Reset";
        int resetX = x + W - 8 - Text.width(reset);
        if (button == 0 && inside(mouseX, mouseY, resetX - 3, y, Text.width(reset) + 6, HEADER)) {
            list.resetAll();
            return true;
        }
        String tour = "Tutorial";
        int tourX = resetX - 12 - Text.width(tour);
        if (button == 0 && inside(mouseX, mouseY, tourX - 3, y, Text.width(tour) + 6, HEADER)) {
            onTutorial.run();
            return true;
        }
        return list.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    void mouseDragged(double mouseX, double mouseY) {
        list.mouseDragged(mouseX);
    }

    @Override
    void mouseReleased(int button) {
        list.mouseReleased();
    }
}
