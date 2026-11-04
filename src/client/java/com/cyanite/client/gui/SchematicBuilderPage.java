package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.litematica.Litematica;
import com.cyanite.client.modules.donut.AuctionBuyer;
import com.cyanite.client.modules.world.SchematicBuilder;
import com.cyanite.client.modules.world.area.AreaSelection;
import com.cyanite.client.modules.world.area.Region;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.Locale;

/**
 * The Schematic Builder tab: which Litematica placement to build and what it takes on the left
 * (every material, with how many you've got on you), the run in the middle (progress, Start /
 * Pause / Resume / Stop), and its settings on the right.
 */
final class SchematicBuilderPage extends ToolPage {
    private static final int LEFT_W = 224;
    private static final int RUN_W = 206;
    private static final int LIST_ROWS = 4;
    private static final int MATERIAL_ROWS = 8;
    private static final int GREEN = 0xFF5BE07A;
    private static final int AMBER = 0xFFFFB347;

    private int listScroll;
    private int materialScroll;
    private Rect listArea = Rect.EMPTY;
    private Rect materialArea = Rect.EMPTY;

    private static SchematicBuilder builder() {
        return Modules.get(SchematicBuilder.class);
    }

    @Override
    protected List<Setting<?>> toolSettings() {
        return builder().settings();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        float a = alpha * entrance();
        frame(dt);
        int total = LEFT_W + GAP + RUN_W + GAP + SETTINGS_W;
        int x = (width - total) / 2;
        int y = TOP + Math.round((1 - entrance()) * -6);
        Rect left = renderSchematic(g, x, y, mouseX, mouseY, dt, a);
        Rect run = renderRun(g, x + LEFT_W + GAP, y, mouseX, mouseY, dt, a);
        Rect set = renderSettings(g, x + LEFT_W + GAP + RUN_W + GAP, y, mouseX, mouseY, dt, a);
        bounds = left.union(run).union(set);
    }

    // ---- The schematic, and what it takes

    private Rect renderSchematic(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        SchematicBuilder b = builder();
        boolean running = b.state() != SchematicBuilder.State.IDLE;
        List<Litematica.Placement> placements = Litematica.installed() ? Litematica.placements() : List.of();
        Litematica.Placement chosen = Litematica.installed() ? b.chosen() : null;
        List<SchematicBuilder.Material> materials = b.materials();

        int rows = Math.max(1, Math.min(LIST_ROWS, placements.size()));
        int listH = Litematica.installed() ? rows * LIST_ROW + 4 + ROW * 2 : ROW * 2;
        int body = PAD + listH + 8 + ROW + MATERIAL_ROWS * ROW + PAD + 12;
        boolean hovered = inside(mouseX, mouseY, x, y, LEFT_W, HEADER + body);
        Panel.draw(g, x, y, LEFT_W, HEADER, body, a, hovered ? 0.6f : 0.35f, 0, 1);
        int titleY = y + (HEADER - Text.CAP) / 2;
        Text.draw(g, "Schematic", x + 8, titleY, Theme.fade(Theme.TEXT, a));
        String from = running ? b.planName() : "From Litematica";
        Text.draw(g, fit(from, LEFT_W - 80), x + LEFT_W - 8 - Text.width(fit(from, LEFT_W - 80)), titleY, Theme.fade(Theme.TEXT_FAINT, a));

        int top = y + HEADER + PAD;
        if (!Litematica.installed()) {
            Text.draw(g, "Litematica isn't installed.", x + PAD + 2, top + 3, Theme.fade(Theme.TEXT_DIM, a));
            Text.draw(g, "It places the schematic where it's built.", x + PAD + 2, top + 3 + ROW, Theme.fade(Theme.TEXT_FAINT, a));
            listArea = Rect.EMPTY;
        } else {
            listScroll = Math.max(0, Math.min(listScroll, placements.size() - rows));
            listArea = new Rect(x, top, LEFT_W, rows * LIST_ROW);
            if (placements.isEmpty()) {
                String empty = "No schematics placed in Litematica";
                Text.draw(g, empty, x + (LEFT_W - Text.width(empty)) / 2, top + (LIST_ROW - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
            }
            for (int i = 0; i < rows && listScroll + i < placements.size(); i++) {
                Litematica.Placement p = placements.get(listScroll + i);
                boolean picked = chosen != null && p.key().equals(chosen.key()) && p.origin().equals(chosen.origin());
                placementRow(g, x, LEFT_W, top + i * LIST_ROW, p, picked, mouseX, mouseY, dt, a, () -> choose(p));
            }
            scrollbar(g, x + LEFT_W - 3, top, rows * LIST_ROW, rows, placements.size(), listScroll, a);
            int rowY = top + rows * LIST_ROW + 4;
            if (chosen != null) {
                Region r = chosen.bounds();
                String turn = turn(chosen);
                row(g, x, LEFT_W, rowY, "Size", r.sizeX() + " × " + r.sizeY() + " × " + r.sizeZ(), Theme.TEXT_DIM, a);
                row(g, x, LEFT_W, rowY + ROW, "Placed", AreaSelection.format(chosen.origin()) + (turn.isEmpty() ? "" : "  ·  " + turn), Theme.TEXT_DIM, a);
            } else {
                Text.draw(g, placements.isEmpty() ? "Place one with Litematica first" : "Click one to build it",
                    x + PAD + 2, rowY + (ROW - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
            }
        }

        // What it takes: one row per material, how many you've got against how many it needs.
        int matTop = top + listH + 8;
        Render2D.pixels(g);
        int s = Render2D.scale();
        Render2D.rect(g, (x + PAD) * s, (matTop - 4) * s, (LEFT_W - PAD * 2) * s, Math.max(1, s / 2), 0, Theme.fade(Theme.BORDER, a));
        Render2D.end(g);
        int textY = matTop + (ROW - Text.CAP) / 2;
        Text.draw(g, running ? "Still to go in" : "Materials", x + PAD + 2, textY, Theme.fade(Theme.TEXT, a));
        String head = "have / need";
        Text.draw(g, head, x + LEFT_W - PAD - 2 - Text.width(head), textY, Theme.fade(Theme.TEXT_FAINT, a));
        int listTop = matTop + ROW;
        materialScroll = Math.max(0, Math.min(materialScroll, materials.size() - MATERIAL_ROWS));
        materialArea = new Rect(x, listTop, LEFT_W, MATERIAL_ROWS * ROW);
        if (materials.isEmpty()) {
            String none = running || chosen != null ? "Nothing left to put in"
                : Litematica.installed() ? "Pick a schematic to see what it takes" : "Shown once a schematic's picked";
            Text.draw(g, none, x + PAD + 2, listTop + (ROW - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
        }
        int short_ = 0;
        for (SchematicBuilder.Material m : materials) if (m.have() < m.need()) short_++;
        for (int i = 0; i < MATERIAL_ROWS && materialScroll + i < materials.size(); i++) {
            SchematicBuilder.Material m = materials.get(materialScroll + i);
            boolean enough = m.have() >= m.need();
            String count = String.format(Locale.ROOT, "%,d / %,d", m.have(), m.need());
            row(g, x, LEFT_W, listTop + i * ROW, AuctionBuyer.name(m.item()), count, enough ? GREEN : AMBER, a);
        }
        scrollbar(g, x + LEFT_W - 3, listTop, MATERIAL_ROWS * ROW, MATERIAL_ROWS, materials.size(), materialScroll, a);
        String idle = materials.isEmpty() ? "" : short_ == 0 ? "You've got everything it needs"
            : builder().settings().stream().anyMatch(st -> st.name.equals("Buy from /ah") && Boolean.TRUE.equals(st.get()))
                ? short_ + " short: bought off /ah as each layer needs them" : short_ + " short: turn on Buy from /ah, or bring them";
        renderNote(g, x, LEFT_W, listTop + MATERIAL_ROWS * ROW + PAD, idle, a);
        return new Rect(x, y, LEFT_W, HEADER + body);
    }

    private void scrollbar(GuiGraphics g, int x, int top, int trackH, int rows, int count, int scroll, float a) {
        if (count <= rows) return;
        int s = Render2D.scale();
        int thumbH = Math.max(8, trackH * rows / count);
        int thumbY = top + (trackH - thumbH) * scroll / Math.max(1, count - rows);
        int w = Math.max(2, s);
        Render2D.pixels(g);
        Render2D.rect(g, x * s - w, thumbY * s, w, thumbH * s, w / 2, Theme.fade(Theme.TEXT_FAINT, a));
        Render2D.end(g);
    }

    // ---- The run

    private Rect renderRun(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        SchematicBuilder b = builder();
        SchematicBuilder.State state = b.state();
        boolean active = state != SchematicBuilder.State.IDLE;
        int body = PAD + 12 + 4 + 10 + 8 + ROW * 6 + 6 + BUTTON_H + PAD + 12;
        boolean hovered = inside(mouseX, mouseY, x, y, RUN_W, HEADER + body);
        Panel.draw(g, x, y, RUN_W, HEADER, body, a, hovered || state == SchematicBuilder.State.RUNNING ? 0.6f : 0.35f, 0, 1);
        int titleY = y + (HEADER - Text.CAP) / 2;
        Text.draw(g, "Schematic Builder", x + 8, titleY, Theme.fade(Theme.TEXT, a));
        int dotColor = switch (state) {
            case RUNNING -> GREEN;
            case PAUSED -> AMBER;
            case IDLE -> Theme.TEXT_FAINT;
        };
        String word = switch (state) {
            case RUNNING -> "Building";
            case PAUSED -> "Paused";
            case IDLE -> "Idle";
        };
        Text.draw(g, word, x + RUN_W - 16 - Text.width(word), titleY, Theme.fade(Theme.TEXT_DIM, a));
        dot(g, x + RUN_W - 10, y + HEADER / 2, dotColor, state == SchematicBuilder.State.RUNNING, a);

        int top = y + HEADER + PAD;
        String line = active ? b.status() : b.report() != null ? b.report() : "Ready when you are";
        Text.draw(g, fit(line, RUN_W - PAD * 2 - 4), x + PAD + 2, top + 2, Theme.fade(active ? Theme.TEXT : Theme.TEXT_DIM, a));

        int barY = top + 12 + 4;
        int barW = RUN_W - PAD * 2 - 34;
        double progress = active ? b.progress() : 0;
        progressBar(g, x + PAD, barY, barW, progress, a);
        String pct = active ? Math.round(progress * 100) + "%" : "-";
        Text.draw(g, pct, x + RUN_W - PAD - 2 - Text.width(pct), barY + 1, Theme.fade(active ? Theme.accentText() : Theme.TEXT_FAINT, a));

        int rowY = barY + 10 + 8;
        row(g, x, RUN_W, rowY, "Built", active ? String.format(Locale.ROOT, "%,d of %,d", b.built(), b.total()) : "-", Theme.TEXT_DIM, a);
        int[] layer = b.layer();
        row(g, x, RUN_W, rowY + ROW, "Layer", active && layer[1] > 0 ? layer[0] + " of " + layer[1] : "-", Theme.TEXT_DIM, a);
        String money = active && (b.spent() > 0 || b.earned() > 0)
            ? dollars(b.spent()) + " spent" + (b.earned() > 0 ? ", " + dollars(b.earned()) + " back" : "") : "-";
        row(g, x, RUN_W, rowY + ROW * 2, "Money", money, Theme.TEXT_DIM, a);
        int marked = b.standIns() + b.leftEmptyCount();
        String marks = !active ? "-" : marked == 0 ? "None"
            : (b.standIns() > 0 ? b.standIns() + " stand-in" + (b.standIns() == 1 ? "" : "s") : "")
            + (b.standIns() > 0 && b.leftEmptyCount() > 0 ? ", " : "")
            + (b.leftEmptyCount() > 0 ? b.leftEmptyCount() + " left empty" : "");
        row(g, x, RUN_W, rowY + ROW * 3, "Marked", marks, marked > 0 ? AMBER : Theme.TEXT_DIM, a);
        String speed = active && b.rate() > 0 ? String.format(Locale.ROOT, "%,d / min", Math.round(b.rate())) : "-";
        row(g, x, RUN_W, rowY + ROW * 4, "Time", active ? duration(b.elapsedMillis()) + "   " + speed : "-", Theme.TEXT_DIM, a);
        long eta = b.etaMillis();
        row(g, x, RUN_W, rowY + ROW * 5, "Left", active && eta >= 0 ? "about " + duration(eta) : "-", Theme.TEXT_DIM, a);

        int by = rowY + ROW * 6 + 6;
        int bw = (RUN_W - PAD * 2 - 6) / 2;
        boolean inWorld = Minecraft.getInstance().level != null;
        switch (state) {
            case IDLE -> button(g, "start", x + PAD, by, RUN_W - PAD * 2, BUTTON_H, "Start", inWorld && b.chosen() != null,
                mouseX, mouseY, dt, a, this::start);
            case RUNNING -> {
                button(g, "pause", x + PAD, by, bw, BUTTON_H, "Pause", true, mouseX, mouseY, dt, a, b::pause);
                button(g, "stop", x + PAD + bw + 6, by, bw, BUTTON_H, "Stop", true, mouseX, mouseY, dt, a, b::stop);
            }
            case PAUSED -> {
                button(g, "resume", x + PAD, by, bw, BUTTON_H, "Resume", true, mouseX, mouseY, dt, a, b::resume);
                button(g, "stop", x + PAD + bw + 6, by, bw, BUTTON_H, "Stop", true, mouseX, mouseY, dt, a, b::stop);
            }
        }
        String hint = !inWorld ? "Join a world to start" : state == SchematicBuilder.State.IDLE
            ? b.chosen() == null ? "Pick a schematic, then Start" : "Stand near it, then Start" : "Keeps going while you're tabbed out";
        Text.draw(g, hint, x + PAD + 2, by + BUTTON_H + PAD, Theme.fade(Theme.TEXT_FAINT, a));
        return new Rect(x, y, RUN_W, HEADER + body);
    }

    private static String dollars(double v) {
        if (v >= 1e9) return String.format(Locale.ROOT, "$%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.ROOT, "$%.2fM", v / 1e6);
        if (v >= 1e4) return String.format(Locale.ROOT, "$%.1fK", v / 1e3);
        return String.format(Locale.ROOT, "$%,d", Math.round(v));
    }

    // ---- Actions

    private void choose(Litematica.Placement p) {
        if (builder().state() != SchematicBuilder.State.IDLE) {
            setNote("Stop the builder to pick another", true);
            return;
        }
        builder().choose(p);
        materialScroll = 0;
        setNote("Picked " + p.name(), false);
    }

    private void start() {
        String error = builder().start();
        if (error != null) setNote(error, true);
    }

    // ---- Input

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        return clickButtonsOrSettings(mouseX, mouseY, button);
    }

    @Override
    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (scrollSettings(mouseX, mouseY, amount)) return true;
        int step = (int) Math.signum(amount);
        if (inside(mouseX, mouseY, materialArea.x(), materialArea.y(), materialArea.w(), materialArea.h())) {
            materialScroll = Math.max(0, materialScroll - step);
            return true;
        }
        if (inside(mouseX, mouseY, listArea.x(), listArea.y(), listArea.w(), listArea.h())) {
            listScroll = Math.max(0, listScroll - step);
            return true;
        }
        return false;
    }
}
