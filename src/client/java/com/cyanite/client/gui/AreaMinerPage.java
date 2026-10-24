package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.litematica.Litematica;
import com.cyanite.client.modules.world.AreaMiner;
import com.cyanite.client.modules.world.area.AreaSelection;
import com.cyanite.client.modules.world.area.AreaTarget;
import com.cyanite.client.modules.world.area.Region;
import com.cyanite.client.system.Modules;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Area Miner tab: what to dig on the left (two corners, or a Litematica placement), the
 * run itself in the middle (progress, Start / Pause / Resume / Stop), and its settings on the right.
 */
final class AreaMinerPage extends Page {
    private static final int AREA_W = 224;
    private static final int RUN_W = 206;
    private static final int SETTINGS_W = 168;
    private static final int GAP = 8;
    private static final int HEADER = 18;
    private static final int PAD = 6;
    private static final int FIELD = 14;
    private static final int ROW = 14;
    private static final int LIST_ROW = 16;
    private static final int LIST_ROWS = 6;
    private static final int BUTTON_H = 16;
    private static final float STATUS_TIME = 4;

    private final TextField corner1 = new TextField(40, AreaMinerPage::coordChar);
    private final TextField corner2 = new TextField(40, AreaMinerPage::coordChar);
    private final List<Button> buttons = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();
    private SettingsList settings;

    private String note = "";
    private boolean noteError;
    private float noteLeft;
    private int listScroll;
    private int settingsScroll;
    private int settingsX;
    private int settingsY;
    private int settingsH;
    private int settingsViewH;
    private boolean draggingSetting;

    private record Button(String key, int x, int y, int w, int h, Runnable left, Runnable right) {
    }

    private static boolean coordChar(int cp) {
        return cp >= '0' && cp <= '9' || cp == '-' || cp == ' ' || cp == ',';
    }

    private static AreaMiner miner() {
        return Modules.get(AreaMiner.class);
    }

    @Override
    void onShow() {
        AreaSelection s = miner().selection();
        corner1.setText(AreaSelection.format(s.pos1));
        corner2.setText(AreaSelection.format(s.pos2));
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        float a = alpha * entrance();
        buttons.clear();
        noteLeft = Math.max(0, noteLeft - dt);
        if (settings == null) settings = new SettingsList(miner().settings());

        syncCorner(corner1, true);
        syncCorner(corner2, false);

        int total = AREA_W + GAP + RUN_W + GAP + SETTINGS_W;
        int x = (width - total) / 2;
        int y = TOP + Math.round((1 - entrance()) * -6);
        Rect area = renderArea(g, x, y, mouseX, mouseY, dt, a);
        Rect run = renderRun(g, x + AREA_W + GAP, y, mouseX, mouseY, dt, a);
        Rect set = renderSettings(g, x + AREA_W + GAP + RUN_W + GAP, y, mouseX, mouseY, dt, a);
        bounds = area.union(run).union(set);
    }

    /** Typing a full "x y z" into a box sets that corner; otherwise the box follows the corner. */
    private void syncCorner(TextField field, boolean first) {
        AreaSelection s = miner().selection();
        BlockPos current = first ? s.pos1 : s.pos2;
        if (!field.isFocused()) {
            String text = AreaSelection.format(current);
            if (!field.text().equals(text)) field.setText(text);
            return;
        }
        BlockPos typed = AreaSelection.parse(field.text());
        if (typed != null && !typed.equals(current) && miner().state() == AreaMiner.State.IDLE) {
            if (first) s.pos1 = typed;
            else s.pos2 = typed;
            miner().selectionChanged();
        }
    }

    // ---- Area

    private Rect renderArea(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        AreaSelection s = miner().selection();
        boolean litematica = s.source == AreaSelection.Source.LITEMATICA;
        List<Litematica.Placement> placements = litematica ? Litematica.placements() : List.of();

        int body;
        if (!litematica) {
            body = PAD + (FIELD + 5) * 2 + (Litematica.installed() ? ROW : 0) + 4 + ROW * 2 + PAD + 12;
        } else if (!Litematica.installed()) {
            body = PAD + ROW * 2 + PAD;
        } else {
            int rows = Math.max(1, Math.min(LIST_ROWS, placements.size()));
            body = PAD + rows * LIST_ROW + 4 + ROW * 3 + PAD + 12;
        }
        boolean hovered = inside(mouseX, mouseY, x, y, AREA_W, HEADER + body);
        boolean typing = corner1.isFocused() || corner2.isFocused();
        Panel.draw(g, x, y, AREA_W, HEADER, body, a, hovered || typing ? 0.6f : 0.35f, 0, 1);
        int titleY = y + (HEADER - Text.CAP) / 2;
        Text.draw(g, "Area", x + 8, titleY, Theme.fade(Theme.TEXT, a));

        // Source switch in the header, right-aligned: Litematica, then Corners to its left.
        String lite = "Litematica";
        String corners = "Corners";
        int liteX = x + AREA_W - 8 - Text.width(lite);
        int cornersX = liteX - 10 - Text.width(corners);
        textButton(g, "src:lite", lite, liteX, titleY, mouseX, mouseY, dt, a,
            litematica ? Theme.accentText() : Theme.TEXT_FAINT, Theme.accentText(), () -> setSource(AreaSelection.Source.LITEMATICA));
        textButton(g, "src:coords", corners, cornersX, titleY, mouseX, mouseY, dt, a,
            !litematica ? Theme.accentText() : Theme.TEXT_FAINT, Theme.accentText(), () -> setSource(AreaSelection.Source.COORDS));

        int top = y + HEADER + PAD;
        if (!litematica) renderCorners(g, x, top, mouseX, mouseY, dt, a);
        else if (!Litematica.installed()) {
            Text.draw(g, "Litematica isn't installed.", x + PAD + 2, top + 3, Theme.fade(Theme.TEXT_DIM, a));
            Text.draw(g, "Use Corners, or add Litematica to your mods.", x + PAD + 2, top + 3 + ROW, Theme.fade(Theme.TEXT_FAINT, a));
        } else {
            renderPlacements(g, x, top, placements, mouseX, mouseY, dt, a);
        }
        return new Rect(x, y, AREA_W, HEADER + body);
    }

    private void renderCorners(GuiGraphics g, int x, int top, int mouseX, int mouseY, float dt, float a) {
        int rowY = top;
        cornerRow(g, x, rowY, "Corner 1", corner1, 0xFF5BE07A, true, mouseX, mouseY, dt, a);
        rowY += FIELD + 5;
        cornerRow(g, x, rowY, "Corner 2", corner2, 0xFFFFB347, false, mouseX, mouseY, dt, a);
        rowY += FIELD + 5;

        if (Litematica.installed()) {
            textButton(g, "litesel", "Copy Litematica's area selection", x + PAD + 2, rowY + (ROW - Text.CAP) / 2, mouseX, mouseY, dt, a,
                Theme.TEXT_DIM, Theme.accentText(), this::copyLitematicaSelection);
            rowY += ROW;
        }
        rowY += 4;

        AreaTarget target = miner().preview();
        if (target != null) {
            Region r = target.bounds;
            row(g, x, AREA_W, rowY, "Size", r.sizeX() + " × " + r.sizeY() + " × " + r.sizeZ(), Theme.TEXT_DIM, a);
            row(g, x, AREA_W, rowY + ROW, "Blocks", String.format(Locale.ROOT, "%,d", target.volume()), Theme.TEXT_DIM, a);
        } else {
            String error = miner().previewError();
            Text.draw(g, error == null ? "" : error, x + PAD + 2, rowY + (ROW - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
        }
        renderNote(g, x, rowY + ROW * 2 + PAD, "Type x y z, or stand or look and click", a);
    }

    private void cornerRow(GuiGraphics g, int x, int rowY, String label, TextField field, int dot, boolean first,
                           int mouseX, int mouseY, float dt, float a) {
        int s = Render2D.scale();
        int dy = rowY + FIELD / 2;
        Render2D.pixels(g);
        Render2D.rect(g, (x + PAD + 2) * s, (dy - 2) * s, 4 * s, 4 * s, 2 * s, Theme.fade(dot, a));
        Render2D.end(g);
        Text.draw(g, label, x + PAD + 9, rowY + (FIELD - Text.CAP) / 2, Theme.fade(Theme.TEXT_DIM, a));

        String here = "Here";
        String look = "Look";
        int lookX = x + AREA_W - PAD - 2 - Text.width(look);
        int hereX = lookX - 8 - Text.width(here);
        int fieldX = x + PAD + 13 + Text.width("Corner 2");
        field.render(g, fieldX, rowY, hereX - 8 - fieldX, FIELD, "x y z", mouseX, mouseY, dt, a);
        int ty = rowY + (FIELD - Text.CAP) / 2;
        textButton(g, "here:" + first, here, hereX, ty, mouseX, mouseY, dt, a, Theme.TEXT_DIM, Theme.accentText(), () -> setHere(first));
        textButton(g, "look:" + first, look, lookX, ty, mouseX, mouseY, dt, a, Theme.TEXT_DIM, Theme.accentText(), () -> setLook(first));
    }

    private void renderPlacements(GuiGraphics g, int x, int top, List<Litematica.Placement> placements,
                                  int mouseX, int mouseY, float dt, float a) {
        AreaSelection s = miner().selection();
        int rows = Math.max(1, Math.min(LIST_ROWS, placements.size()));
        listScroll = Math.max(0, Math.min(listScroll, placements.size() - rows));

        if (placements.isEmpty()) {
            String empty = "No schematics placed in Litematica";
            Text.draw(g, empty, x + (AREA_W - Text.width(empty)) / 2, top + (LIST_ROW - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
        }
        for (int i = 0; i < rows && listScroll + i < placements.size(); i++) {
            Litematica.Placement p = placements.get(listScroll + i);
            boolean chosen = p.key().equals(s.placementKey) && (p.origin().equals(s.placementOrigin) || s.placementOrigin == null);
            placementRow(g, x, top + i * LIST_ROW, p, chosen, mouseX, mouseY, dt, a);
        }
        if (placements.size() > rows) {
            int sc = Render2D.scale();
            int trackH = rows * LIST_ROW;
            int thumbH = Math.max(8, trackH * rows / placements.size());
            int thumbY = top + (trackH - thumbH) * listScroll / Math.max(1, placements.size() - rows);
            int w = Math.max(2, sc);
            Render2D.pixels(g);
            Render2D.rect(g, (x + AREA_W - 3) * sc - w, thumbY * sc, w, thumbH * sc, w / 2, Theme.fade(Theme.TEXT_FAINT, a));
            Render2D.end(g);
        }

        int rowY = top + rows * LIST_ROW + 4;
        String shape = s.shape == AreaSelection.Shape.BLOCKS ? "Schematic blocks" : "Whole box";
        modeRow(g, x, rowY, "Dig", shape, mouseX, mouseY, dt, a, this::toggleShape);
        AreaTarget target = miner().preview();
        if (target != null) {
            Region r = target.bounds;
            Litematica.Placement chosen = miner().findPlacement();
            String turn = chosen == null ? "" : turn(chosen);
            row(g, x, AREA_W, rowY + ROW, "Size", r.sizeX() + " × " + r.sizeY() + " × " + r.sizeZ()
                + (target.regions.size() > 1 ? "  ·  " + target.regions.size() + " regions" : ""), Theme.TEXT_DIM, a);
            row(g, x, AREA_W, rowY + ROW * 2, "Placed", chosen == null ? "-" : AreaSelection.format(chosen.origin()) + (turn.isEmpty() ? "" : "  ·  " + turn), Theme.TEXT_DIM, a);
        } else {
            String error = miner().previewError();
            Text.draw(g, error == null ? "" : error, x + PAD + 2, rowY + ROW + (ROW - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
        }
        renderNote(g, x, rowY + ROW * 3 + PAD, s.shape == AreaSelection.Shape.BLOCKS
            ? "Only where the schematic has blocks" : "Everything inside the schematic's boxes", a);
    }

    private void placementRow(GuiGraphics g, int x, int rowY, Litematica.Placement p, boolean chosen,
                              int mouseX, int mouseY, float dt, float a) {
        int s = Render2D.scale();
        String key = "pl:" + p.key() + p.origin().asLong();
        boolean over = inside(mouseX, mouseY, x + 3, rowY, AREA_W - 6, LIST_ROW);
        float h = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        Render2D.pixels(g);
        Render2D.rect(g, (x + 3) * s, rowY * s, (AREA_W - 6) * s, LIST_ROW * s, 2 * s, Theme.alpha(0xFFFFFF, h * 10 * a));
        if (chosen) {
            int accent = Theme.accent();
            int barW = Math.max(2, Math.round(1.5f * s));
            int barY = (rowY + 4) * s;
            int barH = (LIST_ROW - 8) * s;
            Render2D.fadeRight(g, (x + 3) * s, rowY * s, (AREA_W - 6) * s, LIST_ROW * s, Theme.alpha(accent, 50 * a));
            Render2D.glow(g, (x + 3) * s, barY, barW, barH, barW / 2, 5 * s, Theme.alpha(accent, 160 * Theme.glow() * a));
            Render2D.rect(g, (x + 3) * s, barY, barW, barH, barW / 2, Theme.fade(accent, a));
        }
        Render2D.end(g);

        Region b = p.bounds();
        String size = b.sizeX() + "×" + b.sizeY() + "×" + b.sizeZ();
        String turn = turn(p);
        String right = turn.isEmpty() ? size : turn + "  " + size;
        int textY = rowY + (LIST_ROW - Text.CAP) / 2;
        int rightX = x + AREA_W - 10 - Text.width(right);
        String name = fit(p.name() + (p.enabled() ? "" : " (hidden)"), rightX - 8 - (x + 10));
        Text.draw(g, name, x + 10, textY, Theme.fade(chosen ? Theme.accentText() : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h), a));
        Text.draw(g, right, rightX, textY, Theme.fade(Theme.TEXT_FAINT, a));
        buttons.add(new Button(key, x + 3, rowY, AREA_W - 6, LIST_ROW, () -> choose(p), null));
    }

    private static String turn(Litematica.Placement p) {
        String rotation = switch (p.rotation()) {
            case CLOCKWISE_90 -> "90°";
            case CLOCKWISE_180 -> "180°";
            case COUNTERCLOCKWISE_90 -> "270°";
            default -> "";
        };
        String mirror = p.mirror() == Mirror.NONE ? "" : "mirrored";
        return (rotation + (rotation.isEmpty() || mirror.isEmpty() ? "" : " ") + mirror).trim();
    }

    // ---- The run

    private Rect renderRun(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        AreaMiner m = miner();
        AreaMiner.State state = m.state();
        boolean active = state != AreaMiner.State.IDLE;
        int body = PAD + 12 + 4 + 10 + 8 + ROW * 6 + 6 + BUTTON_H + PAD + 12;
        boolean hovered = inside(mouseX, mouseY, x, y, RUN_W, HEADER + body);
        Panel.draw(g, x, y, RUN_W, HEADER, body, a, hovered || state == AreaMiner.State.RUNNING ? 0.6f : 0.35f, 0, 1);
        int titleY = y + (HEADER - Text.CAP) / 2;
        Text.draw(g, "Area Miner", x + 8, titleY, Theme.fade(Theme.TEXT, a));
        int dotColor = switch (state) {
            case RUNNING -> 0xFF5BE07A;
            case PAUSED -> 0xFFFFB347;
            case IDLE -> Theme.TEXT_FAINT;
        };
        String word = switch (state) {
            case RUNNING -> m.counting() ? "Scanning" : "Running";
            case PAUSED -> "Paused";
            case IDLE -> "Idle";
        };
        Text.draw(g, word, x + RUN_W - 16 - Text.width(word), titleY, Theme.fade(Theme.TEXT_DIM, a));
        dot(g, x + RUN_W - 10, y + HEADER / 2, dotColor, state == AreaMiner.State.RUNNING, a);

        int top = y + HEADER + PAD;
        String line = active ? m.status() : m.report() != null ? m.report() : "Ready when you are";
        Text.draw(g, fit(line, RUN_W - PAD * 2 - 4), x + PAD + 2, top + 2, Theme.fade(active ? Theme.TEXT : Theme.TEXT_DIM, a));

        // Progress bar.
        int barY = top + 12 + 4;
        int barW = RUN_W - PAD * 2 - 34;
        double progress = active ? m.progress() : 0;
        progressBar(g, x + PAD, barY, barW, progress, a);
        String pct = active ? (m.counting() ? "…" : Math.round(progress * 100) + "%") : "-";
        Text.draw(g, pct, x + RUN_W - PAD - 2 - Text.width(pct), barY + 1, Theme.fade(active ? Theme.accentText() : Theme.TEXT_FAINT, a));

        int rowY = barY + 10 + 8;
        long mined = m.mined();
        long left = m.left();
        row(g, x, RUN_W, rowY, "Mined", active ? String.format(Locale.ROOT, "%,d of %,d", mined, mined + left) : "-", Theme.TEXT_DIM, a);
        String hits = m.edgeHits() > 0 ? String.format(Locale.ROOT, "%,d  (%,d other tool)", m.hits(), m.edgeHits()) : String.format(Locale.ROOT, "%,d", m.hits());
        row(g, x, RUN_W, rowY + ROW, "Hits", active ? hits : "-", Theme.TEXT_DIM, a);
        long skipped = m.skipped();
        row(g, x, RUN_W, rowY + ROW * 2, "Skipped", active ? String.format(Locale.ROOT, "%,d", skipped) : "-",
            skipped > 0 ? Theme.DANGER : Theme.TEXT_DIM, a);
        row(g, x, RUN_W, rowY + ROW * 3, "Drill", active ? m.drillShape() : "-", Theme.TEXT_DIM, a);
        String speed = active && m.rate() > 0 ? String.format(Locale.ROOT, "%,d / min", Math.round(m.rate())) : "-";
        row(g, x, RUN_W, rowY + ROW * 4, "Time", active ? duration(m.elapsedMillis()) + "   " + speed : "-", Theme.TEXT_DIM, a);
        long eta = m.etaMillis();
        row(g, x, RUN_W, rowY + ROW * 5, "Left", active && eta >= 0 ? "about " + duration(eta) : "-", Theme.TEXT_DIM, a);

        int by = rowY + ROW * 6 + 6;
        int bw = (RUN_W - PAD * 2 - 6) / 2;
        boolean inWorld = Minecraft.getInstance().level != null;
        switch (state) {
            case IDLE -> button(g, "start", x + PAD, by, RUN_W - PAD * 2, BUTTON_H, "Start", inWorld && m.preview() != null,
                mouseX, mouseY, dt, a, this::start);
            case RUNNING -> {
                button(g, "pause", x + PAD, by, bw, BUTTON_H, "Pause", true, mouseX, mouseY, dt, a, m::pause);
                button(g, "stop", x + PAD + bw + 6, by, bw, BUTTON_H, "Stop", true, mouseX, mouseY, dt, a, m::stop);
            }
            case PAUSED -> {
                button(g, "resume", x + PAD, by, bw, BUTTON_H, "Resume", true, mouseX, mouseY, dt, a, m::resume);
                button(g, "stop", x + PAD + bw + 6, by, bw, BUTTON_H, "Stop", true, mouseX, mouseY, dt, a, m::stop);
            }
        }

        String hint = !inWorld ? "Join a world to start" : state == AreaMiner.State.IDLE
            ? "Hold your drill, then Start" : "Keeps going while you're tabbed out";
        Text.draw(g, hint, x + PAD + 2, by + BUTTON_H + PAD, Theme.fade(Theme.TEXT_FAINT, a));
        return new Rect(x, y, RUN_W, HEADER + body);
    }

    private void progressBar(GuiGraphics g, int x, int y, int w, double progress, float a) {
        int s = Render2D.scale();
        int h = 8 * s;
        int r = h / 2;
        int filled = (int) Math.round(w * s * Math.max(0, Math.min(1, progress)));
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, h, r, Theme.fade(Theme.TRACK, a));
        if (filled > 0) {
            Render2D.glow(g, x * s, y * s, Math.max(filled, h), h, r, 5 * s, Theme.alpha(accent, 70 * Theme.glow() * a));
            Render2D.rect(g, x * s, y * s, Math.max(filled, h), h, r, Theme.fade(accent, a));
        }
        Render2D.end(g);
    }

    private void dot(GuiGraphics g, int cx, int cy, int color, boolean glow, float a) {
        int s = Render2D.scale();
        int d = 4 * s;
        Render2D.pixels(g);
        if (glow) Render2D.glow(g, cx * s - d / 2, cy * s - d / 2, d, d, d / 2, 5 * s, Theme.alpha(color, 140 * Theme.glow() * a));
        Render2D.rect(g, cx * s - d / 2, cy * s - d / 2, d, d, d / 2, Theme.fade(color, a));
        Render2D.end(g);
    }

    // ---- Settings

    private Rect renderSettings(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        int content = settings.height();
        int maxView = Math.max(80, height - y - HEADER - PAD * 2 - 12);
        settingsViewH = Math.min(content, maxView);
        settingsScroll = Math.max(0, Math.min(settingsScroll, content - settingsViewH));
        int body = PAD + settingsViewH + PAD;
        boolean hovered = inside(mouseX, mouseY, x, y, SETTINGS_W, HEADER + body);
        Panel.draw(g, x, y, SETTINGS_W, HEADER, body, a, hovered ? 0.6f : 0.35f, 0, 1);
        Text.draw(g, "Settings", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));

        settingsX = x + 4;
        settingsY = y + HEADER + PAD;
        settingsH = settingsViewH;
        int s = Render2D.scale();
        boolean overList = inside(mouseX, mouseY, settingsX, settingsY, SETTINGS_W - 8, settingsViewH);
        g.enableScissor(x, settingsY, x + SETTINGS_W, settingsY + settingsViewH);
        settings.render(g, settingsX, settingsY - settingsScroll, SETTINGS_W - 8, mouseX, overList || draggingSetting ? mouseY : -10000, dt, a, true);
        g.disableScissor();
        if (content > settingsViewH) {
            int thumbH = Math.max(10, settingsViewH * settingsViewH / content);
            int thumbY = settingsY + (settingsViewH - thumbH) * settingsScroll / Math.max(1, content - settingsViewH);
            int w = Math.max(2, s);
            Render2D.pixels(g);
            Render2D.rect(g, (x + SETTINGS_W - 2) * s - w, thumbY * s, w, thumbH * s, w / 2, Theme.fade(Theme.TEXT_FAINT, a));
            Render2D.end(g);
        }
        return new Rect(x, y, SETTINGS_W, HEADER + body);
    }

    // ---- Small pieces

    private void row(GuiGraphics g, int x, int w, int y, String label, String value, int valueColor, float a) {
        int textY = y + (ROW - Text.CAP) / 2;
        Text.draw(g, label, x + PAD + 2, textY, Theme.fade(Theme.TEXT_DIM, a));
        String shown = fit(value, w - PAD * 2 - 8 - Text.width(label) - 10);
        Text.draw(g, shown, x + w - PAD - 2 - Text.width(shown), textY, Theme.fade(valueColor, a));
    }

    private void modeRow(GuiGraphics g, int x, int y, String label, String value, int mouseX, int mouseY, float dt, float a, Runnable action) {
        boolean over = inside(mouseX, mouseY, x + 2, y, AREA_W - 4, ROW);
        float h = hover("mode:" + label, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.rect(g, (x + 2) * s, y * s, (AREA_W - 4) * s, ROW * s, 2 * s, Theme.alpha(0xFFFFFF, h * 10 * a));
        Render2D.chevron(g, (x + AREA_W - PAD - 3) * s, y * s + ROW * s / 2, (float) (-Math.PI / 2),
            Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, h), a));
        Render2D.end(g);
        int textY = y + (ROW - Text.CAP) / 2;
        Text.draw(g, label, x + PAD + 2, textY, Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h), a));
        Text.draw(g, value, x + AREA_W - PAD - 9 - Text.width(value), textY, Theme.fade(Theme.accentText(), a));
        buttons.add(new Button("mode:" + label, x + 2, y, AREA_W - 4, ROW, action, action));
    }

    private void renderNote(GuiGraphics g, int x, int y, String idle, float a) {
        if (noteLeft > 0) {
            Text.draw(g, fit(note, AREA_W - PAD * 2 - 4), x + PAD + 2, y, Theme.fade(noteError ? Theme.DANGER : Theme.TEXT_DIM, a * Math.min(1, noteLeft / 0.4f)));
        } else {
            Text.draw(g, idle, x + PAD + 2, y, Theme.fade(Theme.TEXT_FAINT, a));
        }
    }

    private void button(GuiGraphics g, String key, int x, int y, int w, int h, String label, boolean enabled,
                        int mouseX, int mouseY, float dt, float a, Runnable action) {
        boolean over = enabled && inside(mouseX, mouseY, x, y, w, h);
        float hv = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Widgets.button(g, x, y, w, h, label, hv, enabled, a);
        if (enabled) buttons.add(new Button(key, x, y, w, h, action, null));
    }

    private void textButton(GuiGraphics g, String key, String label, int bx, int by, int mouseX, int mouseY,
                            float dt, float a, int color, int hoverColor, Runnable action) {
        int w = Text.width(label);
        boolean over = inside(mouseX, mouseY, bx - 3, by - 3, w + 6, Text.CAP + 6);
        float h = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Text.draw(g, label, bx, by, Theme.fade(Theme.mix(color, hoverColor, h), a));
        buttons.add(new Button(key, bx - 3, by - 3, w + 6, Text.CAP + 6, action, null));
    }

    private float hover(String key, boolean over, float dt) {
        float value = Anim.approach(hover.getOrDefault(key, 0f), over ? 1 : 0, dt, 16);
        hover.put(key, value);
        return value;
    }

    private static String fit(String text, int maxWidth) {
        if (Text.width(text) <= maxWidth) return text;
        String cut = text;
        while (!cut.isEmpty() && Text.width(cut + "…") > maxWidth) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    private static String duration(long millis) {
        long seconds = millis / 1000;
        long h = seconds / 3600;
        long m = seconds / 60 % 60;
        long s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%dh %02dm", h, m) : String.format(Locale.ROOT, "%dm %02ds", m, s);
    }

    private void setNote(String text, boolean error) {
        note = text;
        noteError = error;
        noteLeft = STATUS_TIME;
    }

    // ---- Actions

    private void setSource(AreaSelection.Source source) {
        AreaSelection s = miner().selection();
        if (s.source == source) return;
        if (miner().state() != AreaMiner.State.IDLE) {
            setNote("Stop the miner to change the area", true);
            return;
        }
        s.source = source;
        miner().selectionChanged();
    }

    private void setHere(boolean first) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            setNote("Join a world first", true);
            return;
        }
        setCorner(first, mc.player.blockPosition());
    }

    private void setLook(boolean first) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            setNote("Join a world first", true);
            return;
        }
        HitResult hit = mc.player.pick(128, 1, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            setNote("You're not looking at a block", true);
            return;
        }
        setCorner(first, block.getBlockPos());
    }

    private void setCorner(boolean first, BlockPos pos) {
        if (miner().state() != AreaMiner.State.IDLE) {
            setNote("Stop the miner to change the area", true);
            return;
        }
        AreaSelection s = miner().selection();
        if (first) s.pos1 = pos.immutable();
        else s.pos2 = pos.immutable();
        miner().selectionChanged();
        (first ? corner1 : corner2).setText(AreaSelection.format(pos));
        setNote((first ? "Corner 1" : "Corner 2") + " set to " + AreaSelection.format(pos), false);
    }

    private void copyLitematicaSelection() {
        if (miner().state() != AreaMiner.State.IDLE) {
            setNote("Stop the miner to change the area", true);
            return;
        }
        List<Region> boxes = Litematica.selection();
        if (boxes.isEmpty()) {
            setNote("Litematica has no area selected", true);
            return;
        }
        Region r = boxes.getFirst();
        for (Region b : boxes) r = r.union(b);
        AreaSelection s = miner().selection();
        s.pos1 = new BlockPos(r.minX(), r.minY(), r.minZ());
        s.pos2 = new BlockPos(r.maxX(), r.maxY(), r.maxZ());
        miner().selectionChanged();
        corner1.setText(AreaSelection.format(s.pos1));
        corner2.setText(AreaSelection.format(s.pos2));
        setNote(boxes.size() > 1 ? "Copied the box around all " + boxes.size() + " boxes" : "Copied Litematica's selection", false);
    }

    private void choose(Litematica.Placement p) {
        if (miner().state() != AreaMiner.State.IDLE) {
            setNote("Stop the miner to change the area", true);
            return;
        }
        AreaSelection s = miner().selection();
        s.placementKey = p.key();
        s.placementName = p.name();
        s.placementOrigin = p.origin();
        miner().selectionChanged();
        setNote("Picked " + p.name(), false);
    }

    private void toggleShape() {
        if (miner().state() != AreaMiner.State.IDLE) {
            setNote("Stop the miner to change the area", true);
            return;
        }
        AreaSelection s = miner().selection();
        s.shape = s.shape == AreaSelection.Shape.BOX ? AreaSelection.Shape.BLOCKS : AreaSelection.Shape.BOX;
        miner().selectionChanged();
    }

    private void start() {
        String error = miner().start();
        if (error != null) setNote(error, true);
    }

    // ---- Input

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean focused1 = miner().selection().source == AreaSelection.Source.COORDS && corner1.mouseClicked(mouseX, mouseY);
        boolean focused2 = miner().selection().source == AreaSelection.Source.COORDS && corner2.mouseClicked(mouseX, mouseY);

        for (Button b : List.copyOf(buttons)) {
            if (!inside(mouseX, mouseY, b.x, b.y, b.w, b.h)) continue;
            Runnable action = button == 1 ? b.right : button == 0 ? b.left : null;
            if (action != null) action.run();
            return true;
        }
        if (settings != null && inside(mouseX, mouseY, settingsX, settingsY, SETTINGS_W - 8, settingsH)
            && settings.mouseClicked(mouseX, mouseY, button)) {
            draggingSetting = settings.isDragging();
            return true;
        }
        return focused1 || focused2;
    }

    @Override
    void mouseDragged(double mouseX, double mouseY) {
        if (settings != null) settings.mouseDragged(mouseX);
    }

    @Override
    void mouseReleased(int button) {
        if (settings != null) settings.mouseReleased();
        draggingSetting = false;
    }

    @Override
    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (inside(mouseX, mouseY, settingsX, settingsY, SETTINGS_W - 8, settingsH)) {
            settingsScroll = Math.max(0, settingsScroll - (int) Math.signum(amount) * 14);
            return true;
        }
        int size = Litematica.placements().size();
        listScroll = Math.max(0, Math.min(Math.max(0, size - LIST_ROWS), listScroll - (int) Math.signum(amount)));
        return true;
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        boolean enter = event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
        for (TextField field : new TextField[] {corner1, corner2}) {
            if (!field.isFocused()) continue;
            if (enter) {
                if (AreaSelection.parse(field.text()) == null) setNote("Type three whole numbers: x y z", true);
                field.setFocused(false);
                return true;
            }
            return field.keyPressed(event);
        }
        return false;
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return corner1.charTyped(event) || corner2.charTyped(event);
    }

    @Override
    boolean isTyping() {
        return corner1.isFocused() || corner2.isFocused();
    }
}
