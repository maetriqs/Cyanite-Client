package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.macro.Macro;
import com.cyanite.client.system.macro.MacroStep;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The macro editor, built like Scratch. Your macros on the left; next to them a palette of
 * blocks by colour-coded category; in the middle the script, where blocks dragged from the
 * palette snap into place, C-shaped blocks wrap the blocks inside them and condition blocks drop
 * into the gaps "if" and "repeat until" leave for them. Click a value on a block to change it;
 * the panel on the right has the rest of a block's settings. Drop a block back on the palette to
 * delete it. Codes from friends come in through Import, which shows every block first.
 */
final class MacrosPage extends Page {
    private static final int MARGIN = 10;
    private static final int GAP = 8;
    private static final int LIST_W = 136;
    private static final int PALETTE_W = 188;
    private static final int INSPECT_W = 188;
    private static final int HEADER = 18;
    private static final int PAD = 6;
    private static final int ROW = 16;
    private static final int FIELD = 14;
    private static final int BOTTOM = 22;
    private static final int IMPORT_W = 380;
    private static final float STATUS_TIME = 4;
    private static final float CONFIRM_TIME = 3;

    // Block shapes, in GUI units.
    private static final int BH = 20;
    private static final int CH = 14;
    private static final int PILL_H = 12;
    private static final int ARM = 12;
    private static final int FOOT = 10;
    private static final int ELSE_H = 16;
    private static final int EMPTY = 14;
    private static final int PADX = 6;
    private static final int GAPX = 4;
    private static final int EMPTY_SLOT = 26;
    private static final int MAX_PILL = 110;

    private record Button(String key, int x, int y, int w, int h, Runnable left, Runnable right) {
    }

    /** A block drawn on the canvas, and where it lives: in a list, or in a condition slot. */
    private record Hit(MacroStep block, Rect rect, List<MacroStep> list, MacroStep slotOwner, int slot) {
    }

    private record Pill(MacroStep block, MacroStep.Param param, Rect rect) {
    }

    /** A place a stack block can be dropped: between two blocks, or at the end of a list. */
    private record Insert(List<MacroStep> list, int index, int x, int y) {
    }

    private record SlotTarget(MacroStep owner, int slot, Rect rect) {
    }

    private record PaletteHit(MacroStep.Type type, Rect rect) {
    }

    private enum Mode { CANVAS, PALETTE, GHOST }

    private final List<Button> buttons = new ArrayList<>();
    private final List<TextField> live = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private final List<Pill> pills = new ArrayList<>();
    private final List<Insert> inserts = new ArrayList<>();
    private final List<SlotTarget> slotTargets = new ArrayList<>();
    private final List<PaletteHit> paletteHits = new ArrayList<>();
    private final Map<MacroStep.Type, List<String>> tokens = new HashMap<>();
    private final Map<MacroStep.Type, MacroStep> samples = new HashMap<>();

    private final TextField name = new TextField(Macro.MAX_NAME, cp -> cp >= 32 && cp != 127 && cp != '§');
    private final TextField importCode = new TextField(16384, cp -> cp >= 32 && cp < 127);
    private final TextField inline = new TextField(MacroStep.MAX_TEXT, cp -> cp >= 32 && cp != 127 && cp != '§');
    private final Map<MacroStep.Param, TextField> paramFields = new IdentityHashMap<>();

    private Mode mode = Mode.CANVAS;
    private Macro selected;
    private MacroStep selectedBlock;
    private MacroStep fieldsFor;
    private MacroStep.Group group = MacroStep.Group.CHAT;
    private boolean listening;
    private String confirming;
    private float confirmLeft;
    private int listScroll;
    private int canvasScroll;
    private int canvasContent;
    private int paletteScroll;
    private int paletteContent;
    private Rect macroList = Rect.EMPTY;
    private Rect canvas = Rect.EMPTY;
    private Rect palette = Rect.EMPTY;

    // Dragging.
    private Hit pressHit;
    private MacroStep.Type pressType;
    private Rect pressRect;
    private double pressX;
    private double pressY;
    private MacroStep dragging;
    private boolean dragFromCanvas;
    private double dragX;
    private double dragY;
    private double offX;
    private double offY;

    // Typing straight into a value on a block.
    private MacroStep inlineBlock;
    private MacroStep.Param inlineParam;

    private boolean importing;
    private String decodedFrom = "";
    private Macro decoded;
    private int previewScroll;

    private String status = "";
    private boolean statusError;
    private float statusLeft;

    @Override
    void onShow() {
        List<Macro> macros = Macros.list();
        if (selected == null || !macros.contains(selected)) select(macros.isEmpty() ? null : macros.getFirst());
    }

    private void select(Macro macro) {
        selected = macro;
        selectedBlock = null;
        canvasScroll = 0;
        listening = false;
        closeInline(false);
        name.setFocused(false);
        name.setText(macro == null ? "" : macro.name);
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        float a = alpha * entrance();
        buttons.clear();
        live.clear();
        statusLeft = Math.max(0, statusLeft - dt);
        confirmLeft = Math.max(0, confirmLeft - dt);
        if (confirmLeft == 0) confirming = null;
        if (selected != null && !Macros.list().contains(selected)) select(Macros.list().isEmpty() ? null : Macros.list().getFirst());
        if (selectedBlock != null && selectedBlock != dragging && (selected == null || !contains(selected.steps, selectedBlock))) selectedBlock = null;
        syncFields();

        int mx = importing ? -10000 : mouseX;
        int my = importing ? -10000 : mouseY;
        int top = TOP + Math.round((1 - entrance()) * -6);
        int h = height - top - BOTTOM;
        int paletteX = MARGIN + LIST_W + GAP;
        int canvasX = paletteX + PALETTE_W + GAP;
        int inspectX = width - MARGIN - INSPECT_W;
        int canvasW = inspectX - GAP - canvasX;

        Rect left = renderMacros(g, MARGIN, top, LIST_W, h, mx, my, dt, a);
        renderPalette(g, paletteX, top, PALETTE_W, h, mx, my, dt, a);
        renderEditor(g, canvasX, top, canvasW, h, mx, my, dt, a);
        renderInspector(g, inspectX, top, INSPECT_W, h, mx, my, dt, a);
        bounds = left.union(new Rect(inspectX, top, INSPECT_W, h));

        if (dragging != null) {
            g.nextStratum();
            renderDrag(g, a);
        }
        if (importing) {
            g.nextStratum();
            renderImport(g, mouseX, mouseY, dt, a);
        }
    }

    // ---- Left: the macros

    private Rect renderMacros(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY, float dt, float a) {
        boolean hovered = inside(mouseX, mouseY, x, y, w, h);
        Panel.draw(g, x, y, w, HEADER, h - HEADER, a, hovered ? 0.6f : 0.35f, 0, 1);
        int titleY = y + (HEADER - Text.CAP) / 2;
        Text.draw(g, "Macros", x + 8, titleY, Theme.fade(Theme.TEXT, a));
        String add = "+ New";
        textButton(g, "new", add, x + w - 8 - Text.width(add), titleY, mouseX, mouseY, dt, a, Theme.accentText(), 0xFFFFFFFF, this::newMacro);

        List<Macro> macros = Macros.list();
        int listY = y + HEADER + 4;
        int footer = ROW * 2 + 14;
        int listH = h - HEADER - 4 - footer - PAD;
        int visible = Math.max(1, listH / 18);
        listScroll = Math.max(0, Math.min(listScroll, macros.size() - visible));
        macroList = new Rect(x, listY, w, listH);
        if (macros.isEmpty()) Text.draw(g, "No macros yet", x + PAD + 4, listY + 6, Theme.fade(Theme.TEXT_FAINT, a));
        Macro running = Macros.runner().macro();
        g.enableScissor(x, listY, x + w, listY + listH);
        for (int i = 0; i < visible && listScroll + i < macros.size(); i++) {
            Macro macro = macros.get(listScroll + i);
            macroRow(g, macro, x, listY + i * 18, w, macro == selected, macro == running, mouseX, mouseY, dt, a);
        }
        g.disableScissor();
        if (macros.size() > visible) scrollbar(g, x + w - 3, listY, listH, listScroll, macros.size(), visible, a);

        int footY = y + h - footer - 2;
        choiceRow(g, "import", x, footY, w, "Import a code", "", mouseX, mouseY, dt, a, this::openImport, this::openImport);
        int gap = Macros.gapMs();
        String value = gap % 1000 == 0 ? gap / 1000 + "s" : String.format(Locale.ROOT, "%.2fs", gap / 1000.0).replaceAll("0+s$", "s");
        choiceRow(g, "gap", x, footY + ROW, w, "Send gap", value, mouseX, mouseY, dt, a, () -> cycleGap(1), () -> cycleGap(-1));
        Text.draw(g, "The key again stops it", x + PAD + 2, footY + ROW * 2 + 3, Theme.fade(Theme.TEXT_FAINT, a));
        return new Rect(x, y, w, h);
    }

    private void macroRow(GuiGraphics g, Macro macro, int x, int rowY, int w, boolean chosen, boolean running,
                          int mouseX, int mouseY, float dt, float a) {
        int s = Render2D.scale();
        String key = "macro:" + System.identityHashCode(macro);
        boolean over = inside(mouseX, mouseY, x + 3, rowY, w - 6, 18);
        float h = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Render2D.pixels(g);
        Render2D.rect(g, (x + 3) * s, rowY * s, (w - 6) * s, 18 * s, 2 * s, Theme.alpha(0xFFFFFF, h * 10 * a));
        if (chosen) accentBar(g, x + 3, rowY, w - 6, 18, a);
        Render2D.end(g);
        String keyName = macro.key == Macro.NO_KEY ? "-" : macro.keyName();
        int keyW = Text.width(keyName);
        int textY = rowY + (18 - Text.CAP) / 2;
        Text.draw(g, keyName, x + w - 10 - keyW, textY, Theme.fade(macro.key == Macro.NO_KEY ? Theme.TEXT_FAINT : Theme.accentText(), a));
        int nameX = x + 10;
        if (running) {
            dot(g, x + 12, rowY + 9, 0xFF5BE07A, a);
            nameX += 8;
        }
        Text.draw(g, fit(macro.name, x + w - 16 - keyW - nameX), nameX, textY,
            Theme.fade(chosen ? Theme.accentText() : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h), a));
        buttons.add(new Button(key, x + 3, rowY, w - 6, 18, () -> select(macro), null));
    }

    // ---- The palette

    private void renderPalette(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY, float dt, float a) {
        paletteHits.clear();
        boolean hovered = inside(mouseX, mouseY, x, y, w, h);
        boolean deleting = dragging != null && inside(dragX, dragY, x, y, w, h);
        Panel.draw(g, x, y, w, HEADER, h - HEADER, a, deleting ? 1f : hovered ? 0.6f : 0.35f, 0, 1);
        Text.draw(g, deleting ? "Drop here to delete" : "Blocks", x + 8, y + (HEADER - Text.CAP) / 2,
            Theme.fade(deleting ? Theme.DANGER : Theme.TEXT, a));

        // Categories, two to a row, each with its colour.
        MacroStep.Group[] groups = MacroStep.Group.values();
        int cy = y + HEADER + 4;
        int colW = (w - PAD * 2) / 2;
        int s = Render2D.scale();
        for (int i = 0; i < groups.length; i++) {
            MacroStep.Group gr = groups[i];
            int gx = x + PAD + (i % 2) * colW;
            int gy = cy + (i / 2) * 13;
            boolean chosen = gr == group;
            boolean over = inside(mouseX, mouseY, gx, gy, colW - 2, 12);
            float hv = hover("group:" + gr, over, dt);
            if (over) g.requestCursor(CursorTypes.POINTING_HAND);
            Render2D.pixels(g);
            if (chosen) Render2D.rect(g, gx * s, gy * s, (colW - 2) * s, 12 * s, 2 * s, Theme.alpha(gr.color, 55 * a));
            else Render2D.rect(g, gx * s, gy * s, (colW - 2) * s, 12 * s, 2 * s, Theme.alpha(0xFFFFFF, hv * 10 * a));
            int d = 6 * s;
            Render2D.rect(g, (gx + 4) * s, gy * s + (12 * s - d) / 2, d, d, d / 2, Theme.fade(0xFF000000 | gr.color, a));
            Render2D.end(g);
            Text.draw(g, gr.label, gx + 13, gy + (12 - Text.CAP) / 2, Theme.fade(chosen ? Theme.TEXT : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, hv), a));
            final MacroStep.Group pick = gr;
            buttons.add(new Button("group:" + gr, gx, gy, colW - 2, 12, () -> {
                group = pick;
                paletteScroll = 0;
            }, null));
        }
        int listTop = cy + ((groups.length + 1) / 2) * 13 + 6;
        int listH = y + h - PAD - listTop;
        palette = new Rect(x, y, w, h);

        // The category's blocks, drawn as the real thing.
        g.enableScissor(x, listTop, x + w, listTop + listH);
        Mode before = mode;
        mode = Mode.PALETTE;
        int by = listTop + 2 - paletteScroll;
        for (MacroStep.Type type : MacroStep.Type.values()) {
            if (type.group != group) continue;
            MacroStep sample = samples.computeIfAbsent(type, MacroStep::new);
            int bh = blockHeight(sample);
            if (type.isCondition()) drawCondition(g, sample, x + PAD, by, null, -1, a);
            else drawBlock(g, sample, x + PAD, by, null, a);
            paletteHits.add(new PaletteHit(type, new Rect(x + PAD, by, Math.min(w - PAD * 2, lineWidth(sample)), bh)));
            by += bh + 7;
        }
        mode = before;
        g.disableScissor();
        paletteContent = by + paletteScroll - listTop;
        paletteScroll = Math.max(0, Math.min(paletteScroll, Math.max(0, paletteContent - listH)));
        if (paletteContent > listH) scrollbar(g, x + w - 3, listTop, listH, paletteScroll, paletteContent, listH, a);
    }

    // ---- Middle: the script

    private void renderEditor(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY, float dt, float a) {
        hits.clear();
        pills.clear();
        inserts.clear();
        slotTargets.clear();
        boolean hovered = inside(mouseX, mouseY, x, y, w, h);
        Panel.draw(g, x, y, w, HEADER, h - HEADER, a, hovered || name.isFocused() || listening ? 0.6f : 0.35f, 0, 1);
        int titleY = y + (HEADER - Text.CAP) / 2;
        Macro macro = selected;
        if (macro == null) {
            canvas = Rect.EMPTY;
            Text.draw(g, "Script", x + 8, titleY, Theme.fade(Theme.TEXT, a));
            String line = Macros.list().isEmpty() ? "Make your first macro, or import one a friend shared" : "Pick a macro on the left";
            Text.draw(g, line, x + (w - Text.width(line)) / 2, y + h / 2 - 20, Theme.fade(Theme.TEXT_DIM, a));
            button(g, "bignew", x + w / 2 - 50, y + h / 2 - 4, 100, 16, "+ New macro", true, mouseX, mouseY, dt, a, this::newMacro);
            return;
        }

        Text.draw(g, "Script", x + 8, titleY, Theme.fade(Theme.TEXT, a));
        int size = macro.size();
        Text.draw(g, size + (size == 1 ? " block" : " blocks"), x + 8 + Text.width("Script") + 6, titleY, Theme.fade(Theme.TEXT_FAINT, a));
        int right = x + w - 8;
        boolean confirm = ("delete:" + System.identityHashCode(macro)).equals(confirming);
        String del = confirm ? "Sure?" : "Delete";
        right -= Text.width(del);
        textButton(g, "delmacro", del, right, titleY, mouseX, mouseY, dt, a, confirm ? Theme.DANGER : Theme.TEXT_FAINT, Theme.DANGER, () -> deleteMacro(macro));
        right -= 10 + Text.width("Copy");
        textButton(g, "dupmacro", "Copy", right, titleY, mouseX, mouseY, dt, a, Theme.TEXT_FAINT, Theme.accentText(), () -> duplicate(macro));
        right -= 10 + Text.width("Share");
        textButton(g, "share", "Share", right, titleY, mouseX, mouseY, dt, a, Theme.TEXT_DIM, Theme.accentText(), () -> share(macro));

        // Name, key, how often.
        int rowY = y + HEADER + PAD;
        int chipW = 72;
        int fieldW = w - PAD * 2 - chipW * 2 - 8;
        name.render(g, x + PAD, rowY, fieldW, FIELD, "Macro name", mouseX, mouseY, dt, a);
        live.add(name);
        String keyLabel = listening ? "Press a key…" : "Key: " + (macro.key == Macro.NO_KEY ? "none" : macro.keyName());
        chip(g, "key", x + PAD + fieldW + 4, rowY, chipW, FIELD, keyLabel, listening, macro.key != Macro.NO_KEY, mouseX, mouseY, dt, a,
            () -> listening = !listening, () -> {
                macro.key = Macro.NO_KEY;
                listening = false;
            });
        chip(g, "repeat", x + PAD + fieldW + 8 + chipW, rowY, chipW, FIELD, "Run " + macro.repeatName().toLowerCase(Locale.ROOT), false, true,
            mouseX, mouseY, dt, a, () -> cycleRepeat(macro, 1), () -> cycleRepeat(macro, -1));

        int runY = rowY + FIELD + 6;
        boolean running = Macros.runner().macro() == macro;
        boolean inWorld = Minecraft.getInstance().player != null;
        if (running) button(g, "stop", x + PAD, runY, 60, 16, "Stop", true, mouseX, mouseY, dt, a, Macros::stop);
        else button(g, "run", x + PAD, runY, 60, 16, "Run", inWorld && !macro.steps.isEmpty(), mouseX, mouseY, dt, a, () -> run(macro));
        String line;
        int lineColor = Theme.TEXT_FAINT;
        if (statusLeft > 0) {
            line = status;
            lineColor = statusError ? Theme.DANGER : Theme.TEXT_DIM;
        } else if (running) {
            MacroStep now = Macros.runner().current();
            line = now == null ? "Running" : "Running: " + now.summary();
            lineColor = Theme.accentText();
        } else if (Macros.runner().lastProblem() != null && Macros.runner().macro() == null) {
            line = "Last run: " + Macros.runner().lastProblem();
            lineColor = Theme.DANGER;
        } else if (!inWorld) {
            line = "Join a world to run it";
        } else {
            line = macro.key == Macro.NO_KEY ? "Give it a key to run it in game" : "Press " + macro.keyName() + " in game to run it";
        }
        Text.draw(g, fit(line, w - PAD * 2 - 72), x + PAD + 68, runY + (16 - Text.CAP) / 2, Theme.fade(lineColor, a));

        // The canvas.
        int cTop = runY + 16 + PAD;
        int cH = y + h - PAD - cTop;
        canvas = new Rect(x + 2, cTop, w - 4, cH);
        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.rect(g, (x + 3) * s, cTop * s, (w - 6) * s, cH * s, 3 * s, Theme.fade(0xFF0B0D11, a));
        Render2D.end(g);
        if (dragging != null && inside(dragX, dragY, canvas.x(), canvas.y() - 20, canvas.w(), canvas.h() + 40)) {
            // Drag near the top or bottom edge to scroll.
            if (dragY < cTop + 14) canvasScroll -= 5;
            if (dragY > cTop + cH - 14) canvasScroll += 5;
        }
        canvasScroll = Math.max(0, Math.min(canvasScroll, Math.max(0, canvasContent - cH + 30)));
        g.enableScissor(x + 3, cTop, x + w - 3, cTop + cH);
        mode = Mode.CANVAS;
        int startY = cTop + 8 - canvasScroll;
        int endY = drawList(g, macro.steps, x + 12, startY, a);
        if (macro.steps.isEmpty()) {
            String hint = "Drag blocks here from the palette";
            Text.draw(g, hint, x + (w - Text.width(hint)) / 2, cTop + 30, Theme.fade(Theme.TEXT_FAINT, a));
        }
        canvasContent = endY - startY;
        if (dragging != null) renderDropHint(g);
        g.disableScissor();
        if (canvasContent + 30 > cH) scrollbar(g, x + w - 4, cTop, cH, canvasScroll, canvasContent + 30, cH, a);

        if (inlineBlock != null) renderInline(g, mouseX, mouseY, dt, a);
    }

    // ---- Block drawing

    private List<String> tokens(MacroStep.Type type) {
        return tokens.computeIfAbsent(type, t -> List.of(t.template.split(" ")));
    }

    private static boolean isParam(String token) {
        return token.startsWith("{") && token.endsWith("}");
    }

    private static boolean isSlot(String token) {
        return token.startsWith("<") && token.endsWith(">");
    }

    private static int pillWidth(String text) {
        return Math.max(14, Math.min(MAX_PILL, Text.width(text) + 8));
    }

    /** How wide a block's top line is: its words, values and condition slots. */
    private int lineWidth(MacroStep b) {
        int w = PADX * 2;
        boolean first = true;
        int slot = 0;
        for (String token : tokens(b.type)) {
            if (!first) w += GAPX;
            first = false;
            if (isParam(token)) {
                MacroStep.Param param = b.param(token.substring(1, token.length() - 1));
                w += pillWidth(param == null ? "?" : b.display(param));
            } else if (isSlot(token)) {
                MacroStep c = slot < b.slots.length ? b.slots[slot] : null;
                slot++;
                w += c == null ? EMPTY_SLOT : lineWidth(c);
            } else {
                w += Text.width(token);
            }
        }
        return Math.max(w, b.type.isCondition() ? 24 : 54);
    }

    private int blockHeight(MacroStep b) {
        return switch (b.type.shape) {
            case C -> BH + Math.max(EMPTY, listHeight(b.body)) + FOOT;
            case C_ELSE -> BH + Math.max(EMPTY, listHeight(b.body)) + ELSE_H + Math.max(EMPTY, listHeight(b.elseBody)) + FOOT;
            case CONDITION -> CH;
            default -> BH;
        };
    }

    private int listHeight(List<MacroStep> list) {
        int h = 0;
        for (MacroStep b : list) h += blockHeight(b);
        return h;
    }

    /** Draws a list of blocks one under another, noting the gaps a dragged block could drop into. */
    private int drawList(GuiGraphics g, List<MacroStep> list, int x, int y, float a) {
        if (mode == Mode.CANVAS) inserts.add(new Insert(list, 0, x, y));
        for (int i = 0; i < list.size(); i++) {
            y += drawBlock(g, list.get(i), x, y, list, a);
            if (mode == Mode.CANVAS) inserts.add(new Insert(list, i + 1, x, y));
        }
        return y;
    }

    /** @return the height it took. */
    private int drawBlock(GuiGraphics g, MacroStep b, int x, int y, List<MacroStep> list, float a) {
        int color = 0xFF000000 | b.type.group.color;
        int w = lineWidth(b);
        boolean running = mode == Mode.CANVAS && Macros.runner().current() == b;
        boolean chosen = mode == Mode.CANVAS && b == selectedBlock;
        if (b.type.holdsBlocks()) {
            int footW = Math.max(60, Math.min(w, 120));
            shell(g, x, y, w, BH, color, true, running, chosen, a);
            int bodyTop = y + BH;
            int bodyEnd = Math.max(bodyTop + EMPTY, drawList(g, b.body, x + ARM, bodyTop, a));
            if (b.body.isEmpty() && mode != Mode.GHOST) emptyBody(g, x + ARM, bodyTop, a);
            fill(g, x, bodyTop - 1, ARM, bodyEnd - bodyTop + 2, color, a);
            int footY = bodyEnd;
            if (b.type.shape == MacroStep.Shape.C_ELSE) {
                fill(g, x, bodyEnd, footW, ELSE_H, color, a);
                Text.draw(g, "else", x + PADX, bodyEnd + (ELSE_H - Text.CAP) / 2, Theme.fade(0xFFFFFFFF, a));
                int elseTop = bodyEnd + ELSE_H;
                int elseEnd = Math.max(elseTop + EMPTY, drawList(g, b.elseBody, x + ARM, elseTop, a));
                if (b.elseBody.isEmpty() && mode != Mode.GHOST) emptyBody(g, x + ARM, elseTop, a);
                fill(g, x, elseTop - 1, ARM, elseEnd - elseTop + 2, color, a);
                footY = elseEnd;
            }
            shell(g, x, footY, footW, FOOT, color, false, false, false, a);
            drawLine(g, b, x, y, BH, a);
            if (mode == Mode.CANVAS) {
                hits.add(new Hit(b, new Rect(x, y, w, BH), list, null, -1));
                hits.add(new Hit(b, new Rect(x, footY, footW, FOOT), list, null, -1));
            }
            return footY + FOOT - y;
        }
        shell(g, x, y, w, BH, color, b.type.shape != MacroStep.Shape.CAP, running, chosen, a);
        drawLine(g, b, x, y, BH, a);
        if (mode == Mode.CANVAS) hits.add(new Hit(b, new Rect(x, y, w, BH), list, null, -1));
        return BH;
    }

    /** A condition block: rounded all the way, so it reads as true-or-false rather than a step. @return its width. */
    private int drawCondition(GuiGraphics g, MacroStep c, int x, int y, MacroStep owner, int slot, float a) {
        int w = lineWidth(c);
        int color = 0xFF000000 | c.type.group.color;
        int s = Render2D.scale();
        int r = CH * s / 2;
        boolean chosen = mode == Mode.CANVAS && c == selectedBlock;
        Render2D.pixels(g);
        if (chosen) Render2D.glow(g, x * s, y * s, w * s, CH * s, r, 4 * s, Theme.alpha(0xFFFFFF, 130 * a));
        Render2D.rect(g, x * s, y * s, w * s, CH * s, r, Theme.fade(Theme.mix(color, 0xFF000000, 0.3f), a));
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, CH * s - 2, r, Theme.fade(color, a));
        Render2D.end(g);
        drawLine(g, c, x, y, CH, a);
        if (mode == Mode.CANVAS && owner != null) hits.add(new Hit(c, new Rect(x, y, w, CH), null, owner, slot));
        return w;
    }

    /** A stack block's body: a rounded block with a darker rim, and a tab under it that the next one hangs from. */
    private void shell(GuiGraphics g, int x, int y, int w, int h, int color, boolean tab, boolean running, boolean chosen, float a) {
        int s = Render2D.scale();
        int r = 3 * s;
        int rim = Theme.mix(color, 0xFF000000, 0.28f);
        Render2D.pixels(g);
        if (running) Render2D.glow(g, x * s, y * s, w * s, h * s, r, 6 * s, Theme.alpha(0xFFD500, 220 * a));
        else if (chosen) Render2D.glow(g, x * s, y * s, w * s, h * s, r, 4 * s, Theme.alpha(0xFFFFFF, 120 * a));
        Render2D.rect(g, x * s, y * s, w * s, h * s, r, Theme.fade(rim, a));
        Render2D.rect(g, x * s, y * s, w * s, h * s - Math.max(1, s), r, Theme.fade(color, a));
        if (tab) Render2D.rect(g, (x + 10) * s, (y + h) * s - s, 12 * s, 3 * s, s, Theme.fade(rim, a));
        // The notch the block above's tab sits in.
        Render2D.rect(g, (x + 10) * s, y * s, 12 * s, Math.max(1, s), 0, Theme.fade(Theme.mix(color, 0xFFFFFFFF, 0.25f), a));
        Render2D.end(g);
    }

    private void fill(GuiGraphics g, int x, int y, int w, int h, int color, float a) {
        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, h * s, 0, Theme.fade(color, a));
        Render2D.end(g);
    }

    private void emptyBody(GuiGraphics g, int x, int y, float a) {
        Text.draw(g, "drop blocks here", x + 6, y + (EMPTY - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
    }

    /** The words, value pills and condition slots along a block's top line. */
    private void drawLine(GuiGraphics g, MacroStep b, int x, int y, int h, float a) {
        int cx = x + PADX;
        int slot = 0;
        int color = 0xFF000000 | b.type.group.color;
        int s = Render2D.scale();
        boolean first = true;
        for (String token : tokens(b.type)) {
            if (!first) cx += GAPX;
            first = false;
            if (isParam(token)) {
                MacroStep.Param param = b.param(token.substring(1, token.length() - 1));
                String value = param == null ? "?" : b.display(param);
                int pw = pillWidth(value);
                int py = y + (h - PILL_H) / 2;
                boolean choice = param != null && param.kind() == MacroStep.Kind.CHOICE;
                boolean editing = b == inlineBlock && param == inlineParam;
                Render2D.pixels(g);
                Render2D.rect(g, cx * s, py * s, pw * s, PILL_H * s, PILL_H * s / 2,
                    Theme.fade(choice ? Theme.mix(color, 0xFF000000, 0.22f) : 0xFFFFFFFF, a));
                Render2D.end(g);
                if (!editing) {
                    String shown = fit(value, pw - 6);
                    Text.draw(g, shown, cx + (pw - Text.width(shown)) / 2, py + (PILL_H - Text.CAP) / 2,
                        Theme.fade(choice ? 0xFFFFFFFF : 0xFF4A4F5C, a));
                }
                if (mode == Mode.CANVAS && param != null) pills.add(new Pill(b, param, new Rect(cx, py, pw, PILL_H)));
                cx += pw;
            } else if (isSlot(token)) {
                int index = slot++;
                MacroStep c = index < b.slots.length ? b.slots[index] : null;
                int sy = y + (h - CH) / 2;
                if (c == null) {
                    Render2D.pixels(g);
                    Render2D.rect(g, cx * s, sy * s, EMPTY_SLOT * s, CH * s, CH * s / 2, Theme.fade(Theme.mix(color, 0xFF000000, 0.32f), a));
                    Render2D.end(g);
                    if (mode == Mode.CANVAS) slotTargets.add(new SlotTarget(b, index, new Rect(cx, sy, EMPTY_SLOT, CH)));
                    cx += EMPTY_SLOT;
                } else {
                    cx += drawCondition(g, c, cx, sy, b, index, a);
                }
            } else {
                Text.draw(g, token, cx, y + (h - Text.CAP) / 2, Theme.fade(0xFFFFFFFF, a));
                cx += Text.width(token);
            }
        }
    }

    // ---- Dragging

    private void renderDrag(GuiGraphics g, float a) {
        Mode before = mode;
        mode = Mode.GHOST;
        int gx = (int) Math.round(dragX - offX);
        int gy = (int) Math.round(dragY - offY);
        if (dragging.type.isCondition()) drawCondition(g, dragging, gx, gy, null, -1, a * 0.9f);
        else drawBlock(g, dragging, gx, gy, null, a * 0.9f);
        mode = before;
    }

    private void renderDropHint(GuiGraphics g) {
        int s = Render2D.scale();
        if (dragging.type.isCondition()) {
            SlotTarget target = slotTarget();
            if (target == null) return;
            Rect r = target.rect();
            Render2D.pixels(g);
            Render2D.glow(g, r.x() * s, r.y() * s, r.w() * s, r.h() * s, r.h() * s / 2, 5 * s, Theme.alpha(0xFFFFFF, 200));
            Render2D.end(g);
        } else {
            Insert at = insertTarget();
            if (at == null) return;
            Render2D.pixels(g);
            Render2D.glow(g, at.x() * s, at.y() * s - s, 90 * s, 2 * s, s, 4 * s, Theme.alpha(0xFFFFFF, 160));
            Render2D.rect(g, at.x() * s, at.y() * s - s, 90 * s, 2 * s, s, 0xFFFFFFFF);
            Render2D.end(g);
        }
    }

    /** Where a stack block let go of here would land: the gap nearest its top left corner. */
    private Insert insertTarget() {
        if (!inside(dragX, dragY, canvas.x(), canvas.y(), canvas.w(), canvas.h())) return null;
        double gx = dragX - offX;
        double gy = dragY - offY;
        Insert best = null;
        double bestCost = Double.MAX_VALUE;
        for (Insert ins : inserts) {
            double cost = Math.abs(gy - ins.y()) + Math.abs(gx - ins.x()) * 0.6;
            if (cost < bestCost) {
                bestCost = cost;
                best = ins;
            }
        }
        return best;
    }

    /** The empty condition slot under a dragged condition, if any. */
    private SlotTarget slotTarget() {
        double gx = dragX - offX;
        double gy = dragY - offY + CH / 2.0;
        SlotTarget best = null;
        double bestDist = 18;
        for (SlotTarget t : slotTargets) {
            Rect r = t.rect();
            double cx = Math.max(r.x(), Math.min(gx, r.x() + r.w()));
            double cy = Math.max(r.y(), Math.min(gy, r.y() + r.h()));
            double d = Math.hypot(gx - cx, gy - cy);
            if (d < bestDist) {
                bestDist = d;
                best = t;
            }
        }
        return best;
    }

    private void beginDrag() {
        if (pressHit != null) {
            Hit hit = pressHit;
            if (hit.slotOwner() != null) hit.slotOwner().slots[hit.slot()] = null;
            else removeById(hit.list(), hit.block());
            if (Macros.runner().macro() == selected) Macros.stop();
            dragging = hit.block();
            dragFromCanvas = true;
            offX = pressX - hit.rect().x();
            offY = pressY - hit.rect().y();
        } else {
            dragging = newBlock(pressType);
            dragFromCanvas = false;
            offX = pressX - pressRect.x();
            offY = pressY - pressRect.y();
        }
        selectedBlock = dragging;
        closeInline(true);
    }

    private void drop() {
        MacroStep block = dragging;
        dragging = null;
        if (selected == null) return;
        boolean onCanvas = inside(dragX, dragY, canvas.x(), canvas.y(), canvas.w(), canvas.h());
        if (!onCanvas) {
            if (dragFromCanvas) setStatus("Deleted " + block.type.label().toLowerCase(Locale.ROOT), false);
            selectedBlock = null;
            return;
        }
        if (!dragFromCanvas && selected.size() + block.size() > Macro.MAX_BLOCKS) {
            setStatus("That's as many blocks as a macro can have", true);
            return;
        }
        if (block.type.isCondition()) {
            SlotTarget target = slotTarget();
            if (target == null) {
                setStatus("Conditions go in the gaps in if, repeat until and wait until", true);
                selectedBlock = null;
                return;
            }
            target.owner().slots[target.slot()] = block;
        } else {
            Insert at = insertTarget();
            if (at == null) return;
            at.list().add(Math.min(at.index(), at.list().size()), block);
        }
        selectedBlock = block;
    }

    private static void removeById(List<MacroStep> list, MacroStep block) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == block) {
                list.remove(i);
                return;
            }
        }
    }

    /** A fresh block, with any block position starting at the one you're looking at. */
    private MacroStep newBlock(MacroStep.Type type) {
        MacroStep step = new MacroStep(type);
        Minecraft mc = Minecraft.getInstance();
        for (MacroStep.Param param : type.params) {
            if (param.kind() == MacroStep.Kind.POS && mc.player != null) {
                HitResult hit = mc.player.pick(64, 1, false);
                step.set(param.key(), hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : mc.player.blockPosition());
            }
        }
        return step;
    }

    // ---- Typing into a value on a block

    private void openInline(MacroStep block, MacroStep.Param param) {
        closeInline(true);
        inlineBlock = block;
        inlineParam = param;
        selectedBlock = block;
        inline.setText(switch (param.kind()) {
            case NUMBER -> number(block.number(param.key()));
            case POS -> block.pos(param.key()) == null ? "" : block.pos(param.key()).getX() + " " + block.pos(param.key()).getY() + " " + block.pos(param.key()).getZ();
            default -> block.text(param.key());
        });
        inline.setFocused(true);
    }

    private void closeInline(boolean commit) {
        if (inlineBlock != null && commit) apply(inlineBlock, inlineParam, inline.text());
        inlineBlock = null;
        inlineParam = null;
        inline.setFocused(false);
    }

    private static void apply(MacroStep block, MacroStep.Param param, String text) {
        switch (param.kind()) {
            case NUMBER -> {
                try {
                    block.set(param.key(), Double.parseDouble(text.strip()));
                } catch (NumberFormatException ignored) {
                    // Not a number: keep what it was.
                }
            }
            case POS -> {
                BlockPos pos = parsePos(text);
                if (pos != null) block.set(param.key(), pos);
            }
            default -> block.set(param.key(), text);
        }
    }

    private void renderInline(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        for (Pill pill : pills) {
            if (pill.block() != inlineBlock || pill.param() != inlineParam) continue;
            Rect r = pill.rect();
            g.nextStratum();
            int w = Math.max(r.w(), inlineParam.kind() == MacroStep.Kind.TEXT ? 150 : 80);
            inline.render(g, r.x(), r.y() - 1, w, PILL_H + 2, "", mouseX, mouseY, dt, a);
            live.add(inline);
            return;
        }
        // Scrolled away or dragged off: stop typing into it.
        closeInline(true);
    }

    // ---- Right: the chosen block's settings

    private void renderInspector(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY, float dt, float a) {
        boolean over = inside(mouseX, mouseY, x, y, w, h);
        Panel.draw(g, x, y, w, HEADER, h - HEADER, a, over ? 0.6f : 0.35f, 0, 1);
        int titleY = y + (HEADER - Text.CAP) / 2;
        MacroStep b = selectedBlock;
        if (b == null || selected == null || b == dragging) {
            Text.draw(g, "Block", x + 8, titleY, Theme.fade(Theme.TEXT, a));
            int cy = y + HEADER + PAD;
            String[] help = {
                "Drag blocks from the palette into the script. They snap in under each other, or inside repeat, forever and if.",
                "Conditions (the round ones) go in the gaps in if, repeat until and wait until. And, or and not take more conditions.",
                "Click a value on a block to change it. Click a block to see all its settings here.",
                "Drop a block back on the palette to delete it. Right click a block to copy it."
            };
            for (String para : help) {
                for (String l : wrap(para, w - PAD * 2 - 4)) {
                    Text.draw(g, l, x + PAD + 2, cy, Theme.fade(Theme.TEXT_FAINT, a));
                    cy += 10;
                }
                cy += 6;
            }
            return;
        }
        int d = 6;
        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.rect(g, (x + 8) * s, (y + (HEADER - d) / 2) * s, d * s, d * s, d * s / 2, Theme.fade(0xFF000000 | b.type.group.color, a));
        Render2D.end(g);
        Text.draw(g, fit(b.type.label(), w - 90), x + 18, titleY, Theme.fade(Theme.TEXT, a));
        String del = "Delete";
        int dx = x + w - 8 - Text.width(del);
        textButton(g, "delblock", del, dx, titleY, mouseX, mouseY, dt, a, Theme.TEXT_FAINT, Theme.DANGER, this::deleteSelected);
        if (!b.type.isCondition()) {
            String copy = "Copy";
            textButton(g, "copyblock", copy, dx - 10 - Text.width(copy), titleY, mouseX, mouseY, dt, a, Theme.TEXT_FAINT, Theme.accentText(),
                () -> duplicateBlock(b));
        }
        int cy = y + HEADER + PAD;
        for (String l : wrap(b.type.description, w - PAD * 2 - 4)) {
            Text.draw(g, l, x + PAD + 2, cy, Theme.fade(Theme.TEXT_DIM, a));
            cy += 10;
        }
        cy += 4;
        if (b.type.params.isEmpty()) Text.draw(g, "Nothing to set", x + PAD + 2, cy, Theme.fade(Theme.TEXT_FAINT, a));
        for (MacroStep.Param param : b.type.params) cy = paramEditor(g, b, param, x, cy, w, mouseX, mouseY, dt, a) + 4;
        for (MacroStep slot : b.slots) {
            if (slot == null) {
                Text.draw(g, "Needs a condition in its gap", x + PAD + 2, cy + 2, Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.DANGER, 0.6f), a));
                cy += 12;
                break;
            }
        }
        if (Macro.risky(b)) {
            Text.draw(g, "Worth a second look before you share or run it", x + PAD + 2, cy + 2, Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.DANGER, 0.5f), a));
        }
    }

    /** @return the y just below the editor. */
    private int paramEditor(GuiGraphics g, MacroStep step, MacroStep.Param param, int x, int y, int w,
                            int mouseX, int mouseY, float dt, float a) {
        int fx = x + PAD;
        int fw = w - PAD * 2;
        switch (param.kind()) {
            case CHOICE -> {
                choiceRow(g, "choice:" + param.key(), x, y, w, param.label(), step.text(param.key()), mouseX, mouseY, dt, a,
                    () -> step.cycle(param.key(), 1), () -> step.cycle(param.key(), -1));
                return y + ROW;
            }
            case TEXT, MODULE -> {
                Text.draw(g, param.label(), fx + 2, y + 1, Theme.fade(Theme.TEXT_DIM, a));
                TextField field = field(param);
                field.render(g, fx, y + 10, fw, FIELD, param.kind() == MacroStep.Kind.MODULE ? "Auto Mine" : "", mouseX, mouseY, dt, a);
                live.add(field);
                int below = y + 10 + FIELD;
                if (param.kind() == MacroStep.Kind.MODULE) {
                    Module match = module(step.text(param.key()));
                    Text.draw(g, match != null ? "Found: " + match.name : "No module by that name", fx + 2, below + 3,
                        Theme.fade(match != null ? Theme.TEXT_FAINT : Theme.DANGER, a));
                    below += 12;
                }
                return below;
            }
            case NUMBER -> {
                int textY = y + (FIELD - Text.CAP) / 2;
                Text.draw(g, param.label(), fx + 2, textY, Theme.fade(Theme.TEXT_DIM, a));
                int bw = 12;
                int plusX = fx + fw - bw;
                int minusX = plusX - bw - 2;
                int fieldW = 56;
                int fieldX = minusX - 4 - fieldW - (param.unit().isEmpty() ? 0 : Text.width(param.unit()) + 3);
                TextField field = field(param);
                field.render(g, fieldX, y, fieldW, FIELD, "0", mouseX, mouseY, dt, a);
                live.add(field);
                if (!param.unit().isEmpty()) Text.draw(g, param.unit(), fieldX + fieldW + 3, textY, Theme.fade(Theme.TEXT_FAINT, a));
                double stepBy = param.step() >= 1 ? param.step() : param.max() >= 60 ? 0.5 : 0.25;
                smallButton(g, "minus:" + param.key(), minusX, y, bw, "-", mouseX, mouseY, dt, a, () -> step.set(param.key(), step.number(param.key()) - stepBy));
                smallButton(g, "plus:" + param.key(), plusX, y, bw, "+", mouseX, mouseY, dt, a, () -> step.set(param.key(), step.number(param.key()) + stepBy));
                return y + FIELD;
            }
            case POS -> {
                Text.draw(g, param.label(), fx + 2, y + 1, Theme.fade(Theme.TEXT_DIM, a));
                String here = "Here";
                String look = "Look";
                int lookX = fx + fw - Text.width(look) - 2;
                int hereX = lookX - 8 - Text.width(here);
                TextField field = field(param);
                field.render(g, fx, y + 10, hereX - 8 - fx, FIELD, "x y z", mouseX, mouseY, dt, a);
                live.add(field);
                int ty = y + 10 + (FIELD - Text.CAP) / 2;
                textButton(g, "here:" + param.key(), here, hereX, ty, mouseX, mouseY, dt, a, Theme.TEXT_DIM, Theme.accentText(), () -> pickHere(step, param));
                textButton(g, "look:" + param.key(), look, lookX, ty, mouseX, mouseY, dt, a, Theme.TEXT_DIM, Theme.accentText(), () -> pickLook(step, param));
                return y + 10 + FIELD;
            }
        }
        return y;
    }

    private TextField field(MacroStep.Param param) {
        return paramFields.computeIfAbsent(param, p -> switch (p.kind()) {
            case NUMBER -> new TextField(12, cp -> cp >= '0' && cp <= '9' || cp == '.' || cp == '-');
            case POS -> new TextField(40, cp -> cp >= '0' && cp <= '9' || cp == '-' || cp == ' ' || cp == ',');
            default -> new TextField(MacroStep.MAX_TEXT, cp -> cp >= 32 && cp != 127 && cp != '§');
        });
    }

    /** Typing into a field changes the block as you go; a field you're not in shows the block's value. */
    private void syncFields() {
        if (selectedBlock != fieldsFor) {
            paramFields.clear();
            fieldsFor = selectedBlock;
        }
        if (selected != null) {
            if (name.isFocused()) selected.name = Macro.cleanName(name.text());
            else if (!name.text().equals(selected.name)) name.setText(selected.name);
        }
        MacroStep step = selectedBlock;
        if (step == null) return;
        for (Map.Entry<MacroStep.Param, TextField> e : paramFields.entrySet()) {
            MacroStep.Param param = e.getKey();
            TextField field = e.getValue();
            String key = param.key();
            if (field.isFocused()) {
                if (param.kind() == MacroStep.Kind.TEXT || param.kind() == MacroStep.Kind.MODULE) step.set(key, field.text());
                else apply(step, param, field.text());
            } else {
                String shown = switch (param.kind()) {
                    case NUMBER -> number(step.number(key));
                    case POS -> step.pos(key) == null ? "" : step.pos(key).getX() + " " + step.pos(key).getY() + " " + step.pos(key).getZ();
                    default -> step.text(key);
                };
                if (!field.text().equals(shown)) field.setText(shown);
            }
        }
    }

    // ---- Import

    private void renderImport(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        int s = Render2D.scale();
        Render2D.pixels(g);
        g.fill(0, 0, width * s, height * s, Theme.alpha(0x04070C, 150 * a));
        Render2D.end(g);
        if (!importCode.text().equals(decodedFrom)) {
            decodedFrom = importCode.text();
            decoded = decodedFrom.isBlank() ? null : Macro.fromShareCode(decodedFrom);
            previewScroll = 0;
        }
        List<Macro.Line> lines = decoded == null ? List.of() : decoded.lines();
        int w = IMPORT_W;
        int previewRows = decoded == null ? 0 : Math.min(12, Math.max(1, lines.size()));
        boolean anyRisky = decoded != null && decoded.anyRisky();
        int body = PAD + 10 + FIELD + PAD + (decoded == null ? 14 : 12 + previewRows * 11 + 6 + (anyRisky ? 22 : 0) + 12) + PAD + 16 + PAD;
        int x = (width - w) / 2;
        int y = Math.max(TOP, (height - HEADER - body) / 2);
        Panel.draw(g, x, y, w, HEADER, body, a, 0.8f, 0, 1);
        Text.draw(g, "Import a macro", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));
        int cy = y + HEADER + PAD;
        Text.draw(g, "Paste the code someone shared with you", x + PAD + 2, cy, Theme.fade(Theme.TEXT_DIM, a));
        cy += 10;
        int pasteW = 44;
        importCode.render(g, x + PAD, cy, w - PAD * 2 - pasteW - 4, FIELD, "CYM1-…", mouseX, mouseY, dt, a);
        live.add(importCode);
        button(g, "paste", x + w - PAD - pasteW, cy, pasteW, FIELD, "Paste", true, mouseX, mouseY, dt, a, () -> {
            importCode.setText(Macro.findCode(Minecraft.getInstance().keyboardHandler.getClipboard()));
            importCode.setFocused(false);
        });
        cy += FIELD + PAD;
        if (decoded == null) {
            String msg = importCode.text().isBlank() ? "Copy a code from chat or Discord, then press Paste" : "That isn't a Cyanite macro code";
            Text.draw(g, msg, x + PAD + 2, cy + 2, Theme.fade(importCode.text().isBlank() ? Theme.TEXT_FAINT : Theme.DANGER, a));
            cy += 14;
        } else {
            int count = decoded.size();
            String head = decoded.name + "  ·  " + count + (count == 1 ? " block" : " blocks") + "  ·  runs " + decoded.repeatName().toLowerCase(Locale.ROOT);
            Text.draw(g, fit(head, w - PAD * 2 - 4), x + PAD + 2, cy, Theme.fade(Theme.accentText(), a));
            cy += 12;
            previewScroll = Math.max(0, Math.min(previewScroll, lines.size() - previewRows));
            for (int i = 0; i < previewRows && previewScroll + i < lines.size(); i++) {
                Macro.Line line = lines.get(previewScroll + i);
                boolean risky = !line.elseHeader() && Macro.risky(line.step());
                String text = "  ".repeat(line.depth()) + (line.elseHeader() ? "else" : line.step().summary());
                Text.draw(g, fit(text, w - PAD * 2 - 40), x + PAD + 6, cy + i * 11, Theme.fade(risky ? Theme.DANGER : Theme.TEXT_DIM, a));
                if (risky) Text.draw(g, "check", x + w - PAD - 2 - Text.width("check"), cy + i * 11, Theme.fade(Theme.DANGER, a));
            }
            if (lines.size() > previewRows) scrollbar(g, x + w - 3, cy, previewRows * 11, previewScroll, lines.size(), previewRows, a);
            cy += previewRows * 11 + 6;
            if (anyRisky) {
                Text.draw(g, "The red blocks can spend money, move items or take you places.", x + PAD + 2, cy, Theme.fade(Theme.DANGER, a));
                Text.draw(g, "Only save it if you trust whoever sent it.", x + PAD + 2, cy + 10, Theme.fade(Theme.DANGER, a));
                cy += 22;
            }
            Text.draw(g, "It comes in with no key, so it can't run until you give it one.", x + PAD + 2, cy, Theme.fade(Theme.TEXT_FAINT, a));
            cy += 12;
        }
        int by = cy + PAD;
        button(g, "saveimport", x + PAD, by, 140, 16, "Save to my macros", decoded != null && Macros.list().size() < Macros.MAX_MACROS,
            mouseX, mouseY, dt, a, this::saveImport);
        String cancel = "Cancel";
        textButton(g, "cancelimport", cancel, x + w - PAD - 4 - Text.width(cancel), by + (16 - Text.CAP) / 2, mouseX, mouseY, dt, a,
            Theme.TEXT_DIM, Theme.accentText(), this::closeImport);
    }

    // ---- Small pieces

    private void choiceRow(GuiGraphics g, String key, int x, int y, int w, String label, String value, int mouseX, int mouseY,
                           float dt, float a, Runnable left, Runnable right) {
        boolean over = inside(mouseX, mouseY, x + 2, y, w - 4, ROW);
        float h = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.rect(g, (x + 2) * s, y * s, (w - 4) * s, ROW * s, 2 * s, Theme.alpha(0xFFFFFF, h * 10 * a));
        Render2D.chevron(g, (x + w - PAD - 3) * s, y * s + ROW * s / 2, (float) (-Math.PI / 2), Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, h), a));
        Render2D.end(g);
        int textY = y + (ROW - Text.CAP) / 2;
        Text.draw(g, label, x + PAD + 2, textY, Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h), a));
        Text.draw(g, value, x + w - PAD - 9 - Text.width(value), textY, Theme.fade(Theme.accentText(), a));
        buttons.add(new Button(key, x + 2, y, w - 4, ROW, left, right));
    }

    private void chip(GuiGraphics g, String key, int x, int y, int w, int h, String label, boolean waiting, boolean set,
                      int mouseX, int mouseY, float dt, float a, Runnable left, Runnable right) {
        boolean over = inside(mouseX, mouseY, x, y, w, h);
        float hv = hover(key, over || waiting, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        int accent = Theme.accent();
        Render2D.pixels(g);
        if (waiting) {
            float pulse = 0.55f + 0.45f * (float) Math.sin(System.nanoTime() / 1e9 * 6);
            Render2D.glow(g, x * s, y * s, w * s, h * s, r, 6 * s, Theme.alpha(accent, 90 * pulse * Theme.glow() * a));
        }
        Render2D.rect(g, x * s, y * s, w * s, h * s, r, Theme.fade(Theme.mix(Theme.BORDER, accent, hv * 0.8f), a));
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, Math.max(0, r - 1), Theme.fade(Theme.FIELD, a));
        Render2D.end(g);
        String shown = fit(label, w - 8);
        int color = waiting ? Theme.accentText() : set ? Theme.mix(Theme.accentText(), Theme.TEXT, hv) : Theme.TEXT_FAINT;
        Text.draw(g, shown, x + (w - Text.width(shown)) / 2, y + (h - Text.CAP) / 2, Theme.fade(color, a));
        buttons.add(new Button(key, x, y, w, h, left, right));
    }

    private void smallButton(GuiGraphics g, String key, int x, int y, int w, String label, int mouseX, int mouseY, float dt, float a, Runnable action) {
        boolean over = inside(mouseX, mouseY, x, y, w, FIELD);
        float hv = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, FIELD * s, r, Theme.fade(Theme.mix(Theme.TRACK, Theme.accent(), hv * 0.6f), a));
        Render2D.end(g);
        Text.draw(g, label, x + (w - Text.width(label)) / 2, y + (FIELD - Text.CAP) / 2, Theme.fade(Theme.mix(Theme.TEXT_DIM, 0xFFFFFFFF, hv), a));
        buttons.add(new Button(key, x, y, w, FIELD, action, null));
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

    private void accentBar(GuiGraphics g, int x, int y, int w, int h, float a) {
        int s = Render2D.scale();
        int accent = Theme.accent();
        int barW = Math.max(2, Math.round(1.5f * s));
        Render2D.fadeRight(g, x * s, y * s, w * s, h * s, Theme.alpha(accent, 50 * a));
        Render2D.glow(g, x * s, (y + 4) * s, barW, (h - 8) * s, barW / 2, 5 * s, Theme.alpha(accent, 160 * Theme.glow() * a));
        Render2D.rect(g, x * s, (y + 4) * s, barW, (h - 8) * s, barW / 2, Theme.fade(accent, a));
    }

    private void dot(GuiGraphics g, int cx, int cy, int color, float a) {
        int s = Render2D.scale();
        int d = 4 * s;
        Render2D.pixels(g);
        Render2D.glow(g, cx * s - d / 2, cy * s - d / 2, d, d, d / 2, 4 * s, Theme.alpha(color, 120 * Theme.glow() * a));
        Render2D.rect(g, cx * s - d / 2, cy * s - d / 2, d, d, d / 2, Theme.fade(color, a));
        Render2D.end(g);
    }

    private void scrollbar(GuiGraphics g, int x, int y, int h, int scroll, int total, int visible, float a) {
        int s = Render2D.scale();
        int thumbH = Math.max(8, h * visible / Math.max(1, total));
        int thumbY = y + (h - thumbH) * scroll / Math.max(1, total - visible);
        int w = Math.max(2, s);
        Render2D.pixels(g);
        Render2D.rect(g, x * s - w, thumbY * s, w, thumbH * s, w / 2, Theme.fade(Theme.TEXT_FAINT, a));
        Render2D.end(g);
    }

    private float hover(String key, boolean over, float dt) {
        float value = Anim.approach(hover.getOrDefault(key, 0f), over ? 1 : 0, dt, 16);
        hover.put(key, value);
        return value;
    }

    private static String fit(String value, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (Text.width(value) <= maxWidth) return value;
        String cut = value;
        while (!cut.isEmpty() && Text.width(cut + "…") > maxWidth) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    private static List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (Text.width(next) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static String number(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
    }

    private static BlockPos parsePos(String text) {
        String[] parts = text.trim().split("[\\s,]+");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Module module(String name) {
        for (Module module : Modules.getAll()) {
            if (module.name.equalsIgnoreCase(name.strip())) return module;
        }
        return null;
    }

    private void setStatus(String text, boolean error) {
        status = text;
        statusError = error;
        statusLeft = STATUS_TIME;
    }

    // ---- Finding blocks in the script

    private static boolean contains(List<MacroStep> list, MacroStep target) {
        for (MacroStep b : list) {
            if (b == target || containsIn(b, target)) return true;
        }
        return false;
    }

    private static boolean containsIn(MacroStep b, MacroStep target) {
        for (MacroStep c : b.slots) {
            if (c != null && (c == target || containsIn(c, target))) return true;
        }
        return contains(b.body, target) || contains(b.elseBody, target);
    }

    /** The list a stack block sits in, or null if it's not in one. */
    private static List<MacroStep> listOf(List<MacroStep> list, MacroStep target) {
        for (MacroStep b : list) {
            if (b == target) return list;
            List<MacroStep> found = listOf(b.body, target);
            if (found == null) found = listOf(b.elseBody, target);
            if (found != null) return found;
        }
        return null;
    }

    /** Takes a condition out of whatever slot holds it. */
    private static boolean removeCondition(List<MacroStep> list, MacroStep target) {
        for (MacroStep b : list) {
            if (removeConditionIn(b, target)) return true;
        }
        return false;
    }

    private static boolean removeConditionIn(MacroStep b, MacroStep target) {
        for (int i = 0; i < b.slots.length; i++) {
            if (b.slots[i] == target) {
                b.slots[i] = null;
                return true;
            }
            if (b.slots[i] != null && removeConditionIn(b.slots[i], target)) return true;
        }
        return removeCondition(b.body, target) || removeCondition(b.elseBody, target);
    }

    // ---- Actions

    private void newMacro() {
        Macro macro = Macros.add(new Macro("Macro " + (Macros.list().size() + 1)));
        if (macro == null) {
            setStatus("That's the most macros you can have", true);
            return;
        }
        select(macro);
        name.setFocused(true);
    }

    private void duplicate(Macro macro) {
        Macro copy = macro.copy();
        copy.name = Macro.cleanName(macro.name + " copy");
        copy.key = Macro.NO_KEY;
        if (Macros.add(copy) == null) {
            setStatus("That's the most macros you can have", true);
            return;
        }
        select(copy);
        setStatus("Copied: give it its own key", false);
    }

    private void deleteMacro(Macro macro) {
        String key = "delete:" + System.identityHashCode(macro);
        if (!key.equals(confirming)) {
            confirming = key;
            confirmLeft = CONFIRM_TIME;
            return;
        }
        confirming = null;
        Macros.remove(macro);
        select(Macros.list().isEmpty() ? null : Macros.list().getFirst());
        setStatus("Deleted " + macro.name, false);
    }

    private void share(Macro macro) {
        Minecraft.getInstance().keyboardHandler.setClipboard(macro.shareCode());
        setStatus("Code copied: paste it to a friend (your key isn't in it)", false);
    }

    private void run(Macro macro) {
        Macros.start(macro);
        // Moving and looking wait while a menu is open, so get out of the way.
        Minecraft.getInstance().setScreen(null);
    }

    private void cycleRepeat(Macro macro, int direction) {
        List<Integer> repeats = Macro.REPEATS;
        int i = Math.max(0, repeats.indexOf(macro.repeat));
        macro.repeat = repeats.get(Math.floorMod(i + direction, repeats.size()));
    }

    private void cycleGap(int direction) {
        List<Integer> gaps = Macros.GAPS;
        int i = gaps.indexOf(Macros.gapMs());
        Macros.setGapMs(gaps.get(Math.floorMod(i < 0 ? 1 : i + direction, gaps.size())));
    }

    private void deleteSelected() {
        MacroStep b = selectedBlock;
        if (b == null || selected == null) return;
        if (Macros.runner().macro() == selected) Macros.stop();
        if (b.type.isCondition()) {
            removeCondition(selected.steps, b);
        } else {
            List<MacroStep> list = listOf(selected.steps, b);
            if (list != null) removeById(list, b);
        }
        selectedBlock = null;
        closeInline(false);
    }

    private void duplicateBlock(MacroStep b) {
        if (selected == null || b.type.isCondition()) return;
        if (selected.size() + b.size() > Macro.MAX_BLOCKS) {
            setStatus("That's as many blocks as a macro can have", true);
            return;
        }
        List<MacroStep> list = listOf(selected.steps, b);
        if (list == null) return;
        MacroStep copy = b.copy();
        list.add(list.indexOf(b) + 1, copy);
        selectedBlock = copy;
    }

    /** Clicking a palette block adds it: stack blocks after the chosen one, conditions into its first free gap. */
    private void addFromPalette(MacroStep.Type type) {
        if (selected == null) return;
        if (selected.size() >= Macro.MAX_BLOCKS) {
            setStatus("That's as many blocks as a macro can have", true);
            return;
        }
        MacroStep block = newBlock(type);
        if (type.isCondition()) {
            MacroStep owner = selectedBlock;
            if (owner != null) {
                for (int i = 0; i < owner.slots.length; i++) {
                    if (owner.slots[i] == null) {
                        owner.slots[i] = block;
                        selectedBlock = block;
                        return;
                    }
                }
            }
            setStatus("Drag conditions into the gap in an if, repeat until or wait until", true);
            return;
        }
        List<MacroStep> list = selectedBlock == null || selectedBlock.type.isCondition() ? null : listOf(selected.steps, selectedBlock);
        if (list == null) selected.steps.add(block);
        else list.add(list.indexOf(selectedBlock) + 1, block);
        selectedBlock = block;
    }

    private void pickHere(MacroStep step, MacroStep.Param param) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            setStatus("Join a world first", true);
            return;
        }
        step.set(param.key(), mc.player.blockPosition());
    }

    private void pickLook(MacroStep step, MacroStep.Param param) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            setStatus("Join a world first", true);
            return;
        }
        HitResult hit = mc.player.pick(128, 1, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            setStatus("You're not looking at a block", true);
            return;
        }
        step.set(param.key(), block.getBlockPos());
    }

    private void openImport() {
        importing = true;
        importCode.clear();
        decodedFrom = "";
        decoded = null;
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip != null && clip.contains("CYM1-")) importCode.setText(Macro.findCode(clip));
    }

    private void closeImport() {
        importing = false;
        importCode.setFocused(false);
    }

    private void saveImport() {
        if (decoded == null) return;
        Macro macro = decoded.copy();
        macro.key = Macro.NO_KEY;
        if (Macros.add(macro) == null) {
            setStatus("That's the most macros you can have", true);
            return;
        }
        closeImport();
        select(macro);
        setStatus("Saved " + macro.name + ": give it a key to use it", false);
    }

    // ---- Input

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (importing) {
            importCode.mouseClicked(mouseX, mouseY);
            for (Button b : List.copyOf(buttons)) {
                if (!inside(mouseX, mouseY, b.x, b.y, b.w, b.h)) continue;
                if (!b.key.endsWith("import") && !b.key.equals("paste")) continue;
                if (button == 0 && b.left != null) b.left.run();
                return true;
            }
            return true;
        }
        // Clicking off a value being typed into keeps what was typed.
        if (inlineBlock != null) {
            if (inline.mouseClicked(mouseX, mouseY)) return true;
            closeInline(true);
        }
        for (TextField field : live) {
            if (field != inline) field.mouseClicked(mouseX, mouseY);
        }

        for (Button b : List.copyOf(buttons)) {
            if (!inside(mouseX, mouseY, b.x, b.y, b.w, b.h)) continue;
            Runnable action = button == 1 ? b.right : button == 0 ? b.left : null;
            if (!b.key.equals("delmacro")) confirming = null;
            if (!b.key.equals("key")) listening = false;
            if (action != null) action.run();
            return true;
        }
        confirming = null;
        listening = false;

        if (inside(mouseX, mouseY, canvas.x(), canvas.y(), canvas.w(), canvas.h())) {
            // A value on a block: choices flip through their options, the rest you type into.
            for (int i = pills.size() - 1; i >= 0; i--) {
                Pill pill = pills.get(i);
                Rect r = pill.rect();
                if (!inside(mouseX, mouseY, r.x(), r.y(), r.w(), r.h())) continue;
                if (pill.param().kind() == MacroStep.Kind.CHOICE) {
                    pill.block().cycle(pill.param().key(), button == 1 ? -1 : 1);
                    selectedBlock = pill.block();
                } else if (button == 0) {
                    openInline(pill.block(), pill.param());
                }
                return true;
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                Rect r = hit.rect();
                if (!inside(mouseX, mouseY, r.x(), r.y(), r.w(), r.h())) continue;
                if (button == 1) {
                    duplicateBlock(hit.block());
                    return true;
                }
                if (button == 0) {
                    pressHit = hit;
                    pressType = null;
                    pressX = mouseX;
                    pressY = mouseY;
                    dragX = mouseX;
                    dragY = mouseY;
                }
                return true;
            }
            if (button == 0) selectedBlock = null;
            return true;
        }
        if (button == 0) {
            for (PaletteHit hit : paletteHits) {
                Rect r = hit.rect();
                if (!inside(mouseX, mouseY, r.x(), r.y(), r.w(), r.h())) continue;
                if (selected == null) {
                    setStatus("Make a macro first", true);
                    return true;
                }
                pressHit = null;
                pressType = hit.type();
                pressRect = r;
                pressX = mouseX;
                pressY = mouseY;
                dragX = mouseX;
                dragY = mouseY;
                return true;
            }
        }
        return true;
    }

    @Override
    void mouseDragged(double mouseX, double mouseY) {
        dragX = mouseX;
        dragY = mouseY;
        if (dragging == null && (pressHit != null || pressType != null) && Math.hypot(mouseX - pressX, mouseY - pressY) > 3) beginDrag();
    }

    @Override
    void mouseReleased(int button) {
        if (dragging != null) {
            drop();
        } else if (pressHit != null) {
            selectedBlock = pressHit.block();
        } else if (pressType != null) {
            addFromPalette(pressType);
        }
        pressHit = null;
        pressType = null;
    }

    @Override
    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int d = -(int) Math.signum(amount);
        if (importing) {
            previewScroll = Math.max(0, previewScroll + d);
        } else if (inside(mouseX, mouseY, macroList.x(), macroList.y(), macroList.w(), macroList.h())) {
            listScroll = Math.max(0, listScroll + d);
        } else if (inside(mouseX, mouseY, canvas.x(), canvas.y(), canvas.w(), canvas.h())) {
            canvasScroll = Math.max(0, canvasScroll + d * 24);
        } else if (inside(mouseX, mouseY, palette.x(), palette.y(), palette.w(), palette.h())) {
            paletteScroll = Math.max(0, paletteScroll + d * 24);
        }
        return true;
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (listening && selected != null) {
            if (key != GLFW.GLFW_KEY_ESCAPE) {
                selected.key = key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE ? Macro.NO_KEY : key;
                for (Macro other : Macros.list()) {
                    if (other != selected && other.key == selected.key && selected.key != Macro.NO_KEY) {
                        setStatus(other.name + " uses " + Macro.keyName(selected.key) + " too; the first in the list wins", true);
                    }
                }
            }
            listening = false;
            return true;
        }
        boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
        if (inlineBlock != null) {
            if (enter) closeInline(true);
            else if (key == GLFW.GLFW_KEY_ESCAPE) closeInline(false);
            else inline.keyPressed(event);
            return true;
        }
        for (TextField field : live) {
            if (!field.isFocused()) continue;
            if (enter) {
                field.setFocused(false);
                if (field == importCode && decoded != null) saveImport();
                return true;
            }
            return field.keyPressed(event);
        }
        if (importing && key == GLFW.GLFW_KEY_ESCAPE) {
            closeImport();
            return true;
        }
        if (!importing && selectedBlock != null && (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE)) {
            deleteSelected();
            return true;
        }
        return false;
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        if (listening) return true;
        if (inlineBlock != null) return inline.charTyped(event);
        for (TextField field : live) {
            if (field.isFocused()) return field.charTyped(event);
        }
        return false;
    }

    @Override
    boolean isTyping() {
        if (listening || inlineBlock != null) return true;
        for (TextField field : live) {
            if (field.isFocused()) return true;
        }
        return false;
    }
}
