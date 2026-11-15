package com.cyanite.client.gui;

import com.cyanite.client.system.JarCheck;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Says the Cyanite jar in the mods folder isn't the one on GitHub ({@link JarCheck}): what that can
 * mean, and what to do. Shown over the title screen every launch until the jar's replaced.
 */
public class JarWarningScreen extends Screen {
    private static final int LINE = 11;
    private static final int GAP = 6;
    private static final int BODY = 0xFFB8C0CC;
    private static final int DIM = 0xFF8A93A3;

    private final Screen parent;
    private final JarCheck.Result result;

    /** A line of text (null for a blank) and its colour. */
    private record Line(FormattedCharSequence text, int color, int height) {
    }

    private final List<Line> lines = new ArrayList<>();
    private int top;

    public JarWarningScreen(Screen parent, JarCheck.Result result) {
        super(Component.literal("Cyanite jar warning"));
        this.parent = parent;
        this.result = result;
    }

    @Override
    protected void init() {
        lines.clear();
        boolean modified = result.verdict() == JarCheck.Verdict.MODIFIED;
        heading(modified ? "This Cyanite jar has been modified" : "This isn't an official Cyanite jar");
        para(modified
            ? "Cyanite " + result.version() + " is on GitHub, but this jar doesn't match it: someone changed it after it was released. "
                + "Changed copies are how RATs and token stealers get passed around."
            : "There's no Cyanite " + result.version() + " on GitHub, so this jar wasn't released there. Unless you built it yourself, "
                + "treat it as unsafe: changed copies are how RATs and token stealers get passed around.", BODY);
        gap();
        para("Close the game, delete this jar from your mods folder and download Cyanite from GitHub. If you've played with it, "
            + "change your Microsoft and Discord passwords, sign out of other devices and scan your PC.", Theme.TEXT);
        gap();
        para("This jar  " + shortHash(result.sha256()), DIM);
        if (result.expected() != null) para("GitHub's  " + shortHash(result.expected()), DIM);

        int buttons = 20 + 4 + 20;
        top = Math.max(10, (height - contentHeight() - GAP * 2 - buttons) / 2);
        int y = top + contentHeight() + GAP * 2;
        addRenderableWidget(Button.builder(Component.literal("Open GitHub releases"), b -> Util.getPlatform().openUri(URI.create(JarCheck.DOWNLOADS)))
            .bounds(width / 2 - 152, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open mods folder"), b -> Util.getPlatform().openPath(result.jar().getParent()))
            .bounds(width / 2 + 2, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Continue anyway"), b -> onClose())
            .bounds(width / 2 - 152, y + 24, 304, 20).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y = top;
        for (Line line : lines) {
            if (line.text() != null) g.drawCenteredString(font, line.text(), width / 2, y, line.color());
            y += line.height();
        }
    }

    private int contentHeight() {
        int h = 0;
        for (Line line : lines) h += line.height();
        return h;
    }

    private void heading(String text) {
        lines.add(new Line(Component.literal(text).getVisualOrderText(), Theme.DANGER, LINE));
        gap();
    }

    private void para(String text, int color) {
        int wrap = Math.max(120, Math.min(width - 40, 340));
        for (FormattedCharSequence part : font.split(Component.literal(text), wrap)) lines.add(new Line(part, color, LINE));
    }

    private void gap() {
        lines.add(new Line(null, 0, 8));
    }

    /** Enough of a sha256 to compare by eye with the one on the release page. */
    private static String shortHash(String sha) {
        return "sha256 " + sha.substring(0, 16) + "...";
    }
}
