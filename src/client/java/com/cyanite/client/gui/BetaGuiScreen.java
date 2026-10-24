package com.cyanite.client.gui;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.config.Config;
import com.cyanite.client.donut.DonutSmp;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.hud.Hud;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.Setting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Beta UI: one window instead of floating ones. A sidebar gets you anywhere, the middle lists
 * the modules of whatever's picked, and the right shows the picked module's settings. Search
 * covers module names, what they do, every setting and the menu's own pages, and starts as soon as
 * you type. Favourites and whatever's switched on have places of their own at the top.
 */
public class BetaGuiScreen extends Screen {
    private static final float OPEN_TIME = 0.16f;
    private static final float CLOSE_TIME = 0.12f;
    private static final int TOPBAR = 28;
    private static final int SIDE = 124;
    private static final int FOOT = 15;
    private static final int NAV_ROW = 15;
    private static final int NAV_HEAD = 14;
    private static final int NAV_GAP = 3;
    /** A compact panel, not the whole screen; it widens only for the pages that need the room. */
    private static final int WIDTH = 600;
    private static final int HEIGHT = 380;
    private static final Map<String, int[]> WIDE_PAGES = Map.of("area", new int[] {780, 410}, "macros", new int[] {900, 410});
    private static final int LIST_HEAD = 30;
    private static final int ROW = 26;
    private static final int GOLD = 0xFFFFC83D;
    /** Every module has these two; matching them would turn up every module there is. */
    private static final Set<String> COMMON = Set.of("Bind", "Show in list");

    /** Where you were, so the menu opens there again. */
    private static String lastNav = "cat:" + Category.COMBAT.name();
    private static Module lastSelected;
    private static final Set<String> FAVOURITES = new LinkedHashSet<>();

    private enum Kind { FAVOURITES, ACTIVE, CATEGORY, HUD, LOOK, PAGE }

    private record Nav(String key, String label, Kind kind, Category category, Page page, String group) {
    }

    /** A row in the middle list: a module, or a page or Look & feel setting search turned up. */
    private record Row(Module module, Nav nav, String reason, Setting<?> setting) {
    }

    private final Screen parent;
    private final List<Nav> navs = new ArrayList<>();
    private final TextField search = new TextField(48, cp -> cp >= 32 && cp != 127);
    private final SettingsList look = new SettingsList(GuiSettings.ALL);
    private final HudPage hudPage = new HudPage();
    private final String version = FabricLoader.getInstance().getModContainer("cyanite_client")
        .map(mod -> "v" + mod.getMetadata().getVersion().getFriendlyString()).orElse("");
    private final Map<Object, float[]> anim = new IdentityHashMap<>();

    private Nav nav;
    private Module selected;
    private SettingsList inspector;
    private Module inspectorFor;
    /** A setting search matched: lit up in the settings until the search changes. */
    private Setting<?> matched;
    private List<Row> rows = List.of();
    private String rowsFor = "";
    private int cursor = -1;
    private boolean hudMode;
    private Tutorial tutorial;
    /** The key a Bind setting just took: its character mustn't land in the search box too. */
    private int eatKey;

    private float listScroll;
    private float inspectScroll;
    private float lookScroll;
    private int unit = 1;
    private int uiW;
    private int uiH;
    private long lastFrame;
    private float progress;
    private boolean closing;
    private boolean pressed;
    /** What a press started on, so drags and the release go back to it. */
    private Object grabbed;

    /** The window's size, easing towards what the current view wants. */
    private float sizeW = -1;
    private float sizeH = -1;
    // Where things were drawn this frame, for the mouse.
    private int wx;
    private int wy;
    private int ww;
    private int wh;
    private int cx;
    private int cy;
    private int cw;
    private int ch;
    private int listW;
    private int settingsTop;
    private int toggleX;
    private int toggleY;

    public BetaGuiScreen(Screen parent) {
        super(Component.literal("Cyanite Client"));
        this.parent = parent;
        navs.add(new Nav("fav", "Favourites", Kind.FAVOURITES, null, null, null));
        navs.add(new Nav("active", "Switched on", Kind.ACTIVE, null, null, null));
        for (Category c : Category.values()) navs.add(new Nav("cat:" + c.name(), c.title, Kind.CATEGORY, c, null, "Modules"));
        navs.add(new Nav("hud", "HUD editor", Kind.HUD, null, null, "Client"));
        navs.add(new Nav("look", "Look & feel", Kind.LOOK, null, null, "Client"));
        navs.add(new Nav("profiles", "Profiles", Kind.PAGE, null, new ProfilesPage(), "Client"));
        navs.add(new Nav("donut", "Join & commands", Kind.PAGE, null, new DonutPage(), DonutSmp.NAME));
        navs.add(new Nav("accounts", "Accounts", Kind.PAGE, null, new AccountsPage(), DonutSmp.NAME));
        navs.add(new Nav("area", "Area Miner", Kind.PAGE, null, new AreaMinerPage(), "Tools"));
        navs.add(new Nav("macros", "Macros", Kind.PAGE, null, new MacrosPage(), "Tools"));
        nav = navs.stream().filter(n -> n.key.equals(lastNav) && n.kind != Kind.HUD).findFirst().orElse(navs.get(2));
        selected = lastSelected;
        search.setPadLeft(17);
    }

    /** Beta UI was just switched on from the classic menu: open on Look & feel, where that switch lives. */
    static void openOnLook() {
        lastNav = "look";
    }

    // ---- The tour

    /** The classic menu's chooser picked this one: open with its tour. */
    private static boolean tourNext;

    static void tourNext() {
        tourNext = true;
    }

    static boolean tourPending() {
        return tourNext;
    }

    private void startTour() {
        tutorial = new Tutorial(tourSteps(), tab -> tourView(), this::tourDone);
    }

    /** Picked in the chooser: this menu's tour, or over to the classic menu for its own. */
    private void chosen(boolean beta) {
        if (beta) {
            startTour();
        } else {
            // render() sees the switch and hands over to the classic menu, which starts its tour.
            ClickGuiScreen.tourNext();
            GuiSettings.BETA.set(false);
        }
    }

    private void tourDone() {
        ClickGuiScreen.setTutorialSeen(true);
        Config.save();
    }

    /** The tour shows the Combat modules with one picked, so every column has something in it. */
    private void tourView() {
        hudMode = false;
        Nav combat = navs.stream().filter(n -> n.category == Category.COMBAT).findFirst().orElseThrow();
        if (nav != combat || searching()) go(combat);
        if (selected == null || selected.category != Category.COMBAT) {
            modules(combat).stream().findFirst().ifPresent(row -> select(row.module, null));
        }
    }

    private List<Tutorial.Step> tourSteps() {
        return List.of(
            new Tutorial.Step(0, "The Beta UI", "Everything in one window: where to go on the left, the modules in the middle, and the "
                + "picked one's settings on the right.", () -> Rect.EMPTY),
            new Tutorial.Step(0, "Search anything", "Just start typing, wherever you are. It finds modules, what they do, any setting "
                + "and the pages. Enter switches the top match on or off.", this::searchRect),
            new Tutorial.Step(0, "Get around", "Every category of modules, then the HUD editor, Look & feel, profiles, the Donut SMP "
                + "server, accounts, Area Miner and macros.", () -> new Rect(wx + 2, cy + 2, SIDE - 4, ch - 4)),
            new Tutorial.Step(0, "Modules", "Click one to see its settings. Its switch or a right click turns it on, and a dot marks "
                + "the ones that are. The star keeps it in Favourites.", () -> new Rect(cx + 2, cy + 2, listW - 4, ch - 4)),
            new Tutorial.Step(0, "Settings", "Everything the picked module can do. Click a value to change it, drag the sliders, right "
                + "click to reset one. Bind sets a key that switches it in game.", () -> new Rect(cx + listW + 4, cy + 2, cw - listW - 8, ch - 4)),
            new Tutorial.Step(0, "Favourites and what's on", "Starred modules wait in Favourites, and Switched on lists everything "
                + "running. The pill up top counts them too.", () -> new Rect(wx + 4, cy + 6, SIDE - 8, NAV_ROW * 2)),
            new Tutorial.Step(0, "You're all set", "Insert opens Cyanite any time. Replay this tour, or go back to the classic menu, "
                + "from Look & feel.", () -> Rect.EMPTY));
    }

    private Rect searchRect() {
        int fieldX = cx + 10;
        int closeX = wx + ww - 18;
        return new Rect(fieldX, wy + TOPBAR / 2 - 8, Math.min(320, closeX - 80 - fieldX), 16);
    }

    // ---- Saved with the config

    public static JsonArray saveFavourites() {
        JsonArray array = new JsonArray();
        FAVOURITES.forEach(array::add);
        return array;
    }

    public static void loadFavourites(JsonArray array) {
        FAVOURITES.clear();
        for (JsonElement e : array) {
            if (e.isJsonPrimitive()) FAVOURITES.add(e.getAsString());
        }
    }

    /** True while something here is taking typed text, so keys shouldn't move the player. */
    public boolean isTyping() {
        return search.isFocused() || SettingsList.isEditing() || nav.kind == Kind.PAGE && nav.page.isTyping() || hudMode && hudPage.isTyping();
    }

    // ---- Frame

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void updateUnit() {
        Window window = minecraft.getWindow();
        String scale = GuiSettings.SCALE.get();
        if (scale.equals("Auto")) {
            unit = Math.max(1, Math.round(window.getHeight() / 540f));
            // Room for the widest page (Area Miner) beside the sidebar.
            while (unit > 1 && window.getWidth() / unit < 780) unit--;
        } else {
            unit = scale.charAt(0) - '0';
            // A big fixed scale on a small window would crush the settings column to nothing.
            while (unit > 1 && window.getWidth() / unit < 640) unit--;
        }
        uiW = window.getWidth() / unit;
        uiH = window.getHeight() / unit;
        Render2D.setScale(unit);
    }

    private double toUi(double gui) {
        return gui * minecraft.getWindow().getGuiScale() / unit;
    }

    @Override
    protected void init() {
        updateUnit();
        if (nav.page != null) nav.page.onShow();
        if (tourNext) {
            tourNext = false;
            startTour();
        } else if (!ClickGuiScreen.tutorialSeen() && tutorial == null) {
            tutorial = Tutorial.chooser(true, this::chosen, this::tourDone);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float a = Anim.easeOut(progress);
        if (minecraft.level == null) renderPanorama(g, partialTick);
        if (hudMode) a *= 0.4f;
        else if (GuiSettings.BLUR.get()) renderBlurredBackground(g);
        g.fillGradient(0, 0, width, height, Theme.fade(Theme.TINT_TOP, a), Theme.fade(Theme.TINT_BOTTOM, a));
    }

    @Override
    public void render(GuiGraphics g, int guiMouseX, int guiMouseY, float partialTick) {
        // Beta UI switched off from Look & feel: straight back to the classic menu, on its GUI tab.
        if (!GuiSettings.BETA.get()) {
            minecraft.setScreen(new ClickGuiScreen(parent, 2));
            return;
        }
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9f, 0.1f);
        lastFrame = now;
        if (Anim.enabled()) progress = Anim.clamp(progress + (closing ? -dt / CLOSE_TIME : dt / OPEN_TIME));
        else progress = closing ? 0 : 1;
        if (closing && progress <= 0) {
            minecraft.setScreen(parent);
            return;
        }

        updateUnit();
        int mouseX = (int) toUi(guiMouseX);
        int mouseY = (int) toUi(guiMouseY);
        float a = Anim.easeOut(progress);

        g.pose().pushMatrix();
        g.pose().scale((float) unit / minecraft.getWindow().getGuiScale());
        Hud.editing = hudMode;
        // While the tour is up, nothing behind it reacts to the mouse.
        int behindX = tutorial == null ? mouseX : -10000;
        int behindY = tutorial == null ? mouseY : -10000;
        if (hudMode) renderHudMode(g, behindX, behindY, dt, a);
        else styled(() -> {
            renderWindow(g, behindX, behindY, dt, a);
            if (tutorial != null) {
                g.nextStratum();
                tutorial.render(g, uiW, uiH, mouseX, mouseY, dt, a);
                if (tutorial.isFinished()) tutorial = null;
            }
            return null;
        });
        g.pose().popMatrix();
    }

    /**
     * Runs in a smooth system font rather than Minecraft's pixel one, so the menu looks sharp.
     * Input runs in it too, so what's measured for the mouse matches what's drawn. The HUD editor
     * stays in the HUD's own font, so it shows the HUD as it really looks.
     */
    private <T> T styled(java.util.function.Supplier<T> action) {
        if (hudMode) return action.get();
        String previous = Text.using(Text.smoothFamily());
        try {
            return action.get();
        } finally {
            Text.using(previous);
        }
    }

    private void renderHudMode(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        hudPage.resize(uiW, uiH);
        hudPage.shownFor += dt;
        hudPage.render(g, mouseX, mouseY, dt, a);
        g.nextStratum();
        String back = "Back to the menu";
        int bw = Text.width(back) + 20;
        int bx = (uiW - bw) / 2;
        float[] h = anim(back);
        boolean over = Page.inside(mouseX, mouseY, bx, 8, bw, 16);
        h[0] = Anim.approach(h[0], over ? 1 : 0, dt, 16);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Widgets.button(g, bx, 8, bw, 16, back, h[0], true, a);
    }

    private void renderWindow(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        int[] size = searching() ? null : WIDE_PAGES.get(nav.key);
        int targetW = Math.min(uiW - 24, size == null ? WIDTH : size[0]);
        int targetH = Math.min(uiH - 24, size == null ? HEIGHT : size[1]);
        sizeW = sizeW < 0 || !Anim.enabled() ? targetW : Anim.approach(sizeW, targetW, dt, 16);
        sizeH = sizeH < 0 || !Anim.enabled() ? targetH : Anim.approach(sizeH, targetH, dt, 16);
        ww = Math.round(sizeW);
        wh = Math.round(sizeH);
        wx = (uiW - ww) / 2;
        wy = (uiH - wh) / 2 + Math.round((1 - a) * 8);
        cx = wx + SIDE;
        cy = wy + TOPBAR;
        cw = ww - SIDE;
        ch = wh - TOPBAR - FOOT;

        int s = Render2D.scale();
        int accent = Theme.accent();
        int r = Math.round(Theme.radius() * s);

        // The window: shadow, a soft accent glow, border, body; the sidebar a shade lighter.
        Render2D.pixels(g);
        Render2D.glow(g, wx * s, (wy + 3) * s, ww * s, wh * s, r, 14 * s, Theme.fade(Theme.SHADOW, a));
        Render2D.glow(g, wx * s, wy * s, ww * s, wh * s, r, 10 * s, Theme.alpha(accent, 40 * Theme.glow() * a));
        Render2D.rect(g, wx * s, wy * s, ww * s, wh * s, r, Theme.fade(Theme.BORDER, a));
        Render2D.rect(g, wx * s + 1, wy * s + 1, ww * s - 2, wh * s - 2, Math.max(0, r - 1), Theme.fade(0xF20E1015, a));
        g.fill(wx * s + 1, (wy + TOPBAR) * s, cx * s, (wy + wh - FOOT) * s, Theme.fade(0xFF13161C, a));
        // Light falling from the top bar onto the content, very faint.
        g.fillGradient(cx * s, (wy + TOPBAR) * s, (wx + ww) * s - 1, (wy + TOPBAR + 60) * s, Theme.alpha(0xFFFFFF, 7 * a), 0);
        int line = Math.max(1, s / 2);
        g.fill(wx * s + 1, (wy + TOPBAR) * s - line, (wx + ww) * s - 1, (wy + TOPBAR) * s, Theme.fade(Theme.BORDER, a));
        Render2D.fadeRight(g, wx * s + 1, (wy + TOPBAR) * s - line, ww * s * 2 / 3, line, Theme.alpha(accent, 170 * a));
        Render2D.glow(g, (wx + 8) * s, (wy + TOPBAR) * s - line, ww * s / 3, line, 0, 5 * s, Theme.alpha(accent, 50 * Theme.glow() * a));
        g.fill(cx * s - line, (wy + TOPBAR) * s, cx * s, (wy + wh - FOOT) * s, Theme.fade(Theme.BORDER, a));
        g.fill(wx * s + 1, (wy + wh - FOOT) * s, (wx + ww) * s - 1, (wy + wh - FOOT) * s + line, Theme.fade(Theme.BORDER, a));
        Render2D.end(g);

        renderTopBar(g, mouseX, mouseY, dt, a);
        renderSidebar(g, mouseX, mouseY, dt, a);
        if (searching() || nav.kind == Kind.FAVOURITES || nav.kind == Kind.ACTIVE || nav.kind == Kind.CATEGORY) {
            renderList(g, mouseX, mouseY, dt, a);
            renderInspector(g, mouseX, mouseY, dt, a);
        } else if (nav.kind == Kind.LOOK) {
            renderLook(g, mouseX, mouseY, dt, a);
        } else if (nav.kind == Kind.PAGE) {
            renderPage(g, mouseX, mouseY, dt, a);
        }
        renderFooter(g, a);
    }

    private boolean searching() {
        return !search.text().isBlank();
    }

    private float[] anim(Object key) {
        return anim.computeIfAbsent(key, k -> new float[] {0, Float.NaN});
    }

    // ---- Top bar: name, search, how many are on, close

    private void renderTopBar(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        int s = Render2D.scale();
        int accent = Theme.accent();
        int mid = wy + TOPBAR / 2;
        int ty = wy + (TOPBAR - Text.CAP) / 2;

        // The mark: a small glowing gem, then the name and a BETA tag.
        int gx = wx + 17;
        Render2D.pixels(g);
        Render2D.glow(g, (gx - 4) * s, (mid - 4) * s, 8 * s, 8 * s, 4 * s, 7 * s, Theme.alpha(accent, 120 * Theme.glow() * a));
        g.pose().pushMatrix();
        g.pose().translate(gx * s, mid * s);
        g.pose().rotate((float) Math.PI / 4);
        int d = Math.round(3.2f * s);
        g.fill(-d, -d, d, d, Theme.fade(accent, a));
        int core = Math.max(1, Math.round(1.2f * s));
        g.fill(-core, -core, core, core, Theme.fade(Theme.mix(accent, 0xFFFFFFFF, 0.75f), a));
        g.pose().popMatrix();
        Render2D.end(g);
        String name = "Cyanite";
        Text.draw(g, name, gx + 9, ty, Theme.fade(Theme.TEXT, a));
        int bx = gx + 9 + Text.width(name) + 5;
        String beta = "BETA";
        int bw = Text.width(beta) + 7;
        Render2D.pixels(g);
        Render2D.rect(g, bx * s, (mid - 6) * s, bw * s, 12 * s, 3 * s, Theme.alpha(accent, 48 * a));
        Render2D.end(g);
        Text.draw(g, beta, bx + 4, ty, Theme.fade(Theme.accentText(), a));

        int closeX = wx + ww - 18;
        int fieldX = cx + 10;
        int fieldW = Math.min(320, closeX - 80 - fieldX);
        search.render(g, fieldX, mid - 8, fieldW, 16, "Search modules, settings, pages...", mouseX, mouseY, dt, a);
        magnifier(g, fieldX + 9, mid, Theme.fade(search.isFocused() ? Theme.accentText() : Theme.TEXT_DIM, a), a);

        // How many are on, as a pill: a click shows them.
        long on = Modules.getAll().stream().filter(Module::isEnabled).count();
        String active = on + " on";
        int aw = Text.width(active);
        int ax = closeX - 14 - aw;
        boolean overActive = Page.inside(mouseX, mouseY, ax - 4, wy + 6, aw + 8, 18);
        float[] ha = anim("active");
        ha[0] = Anim.approach(ha[0], overActive ? 1 : 0, dt, 16);
        if (overActive) g.requestCursor(CursorTypes.POINTING_HAND);
        Render2D.pixels(g);
        Render2D.rect(g, (ax - 12) * s, (mid - 7) * s, (aw + 18) * s, 14 * s, 7 * s,
            on > 0 ? Theme.alpha(accent, (34 + 30 * ha[0]) * a) : Theme.alpha(0xFFFFFF, (8 + 8 * ha[0]) * a));
        if (on > 0) {
            int dot = Math.max(2, Math.round(2.4f * s));
            Render2D.glow(g, (ax - 6) * s - dot / 2, mid * s - dot / 2, dot, dot, dot / 2, 4 * s, Theme.alpha(accent, 160 * a));
            Render2D.rect(g, (ax - 6) * s - dot / 2, mid * s - dot / 2, dot, dot, dot / 2, Theme.fade(Theme.accentText(), a));
        }
        Render2D.end(g);
        Text.draw(g, active, ax, ty, Theme.fade(on > 0 ? Theme.accentText() : Theme.TEXT_DIM, a));

        float[] h = anim("close");
        boolean overClose = Page.inside(mouseX, mouseY, closeX - 4, wy + 6, 16, 18);
        h[0] = Anim.approach(h[0], overClose ? 1 : 0, dt, 16);
        if (overClose) g.requestCursor(CursorTypes.POINTING_HAND);
        if (h[0] > 0.01f) {
            Render2D.pixels(g);
            Render2D.rect(g, (closeX - 3) * s, (mid - 7) * s, 14 * s, 14 * s, 7 * s, Theme.alpha(Theme.DANGER, 40 * h[0] * a));
            Render2D.end(g);
        }
        SettingsWindow.drawCross(g, closeX + 4, mid, Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.DANGER, h[0]), a));
    }

    /** A magnifying glass centred on (x, y): a ring and a handle. */
    private static void magnifier(GuiGraphics g, int x, int y, int color, float a) {
        int s = Render2D.scale();
        int ring = Math.round(3.3f * s);
        int thick = Math.max(1, Math.round(0.9f * s));
        int icx = x * s - s / 2;
        int icy = y * s - s / 2;
        Render2D.pixels(g);
        Render2D.rect(g, icx - ring, icy - ring, ring * 2, ring * 2, ring, color);
        Render2D.rect(g, icx - ring + thick, icy - ring + thick, (ring - thick) * 2, (ring - thick) * 2, ring - thick, Theme.fade(Theme.FIELD, a));
        g.pose().pushMatrix();
        g.pose().translate(icx + ring * 0.62f, icy + ring * 0.62f);
        g.pose().rotate((float) Math.PI / 4);
        g.fill(0, -thick / 2, Math.round(2.8f * s), thick - thick / 2, color);
        g.pose().popMatrix();
        Render2D.end(g);
    }

    // ---- Bigger text, for titles

    /** Half as big again (twice at the smallest scale), from a font made at that size so it stays sharp. */
    private static int bigScale() {
        int s = Render2D.scale();
        return Math.max(s + 1, Math.round(s * 1.5f));
    }

    private static void drawBig(GuiGraphics g, String text, int x, int y, int color) {
        int s = Render2D.scale();
        int big = bigScale();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale((float) big / s);
        int previous = Render2D.setScale(big);
        Text.draw(g, text, 0, 0, color);
        Render2D.setScale(previous);
        g.pose().popMatrix();
    }

    private static int bigWidth(String text) {
        int s = Render2D.scale();
        int big = bigScale();
        int previous = Render2D.setScale(big);
        int w = Text.width(text);
        Render2D.setScale(previous);
        return (int) Math.ceil(w * big / (double) s);
    }

    private static int bigCap() {
        return (int) Math.ceil(Text.CAP * bigScale() / (double) Render2D.scale());
    }

    // ---- Sidebar

    /** A colour of its own for each category: the accent turned round the colour wheel. */
    private static int hue(Category c) {
        int accent = Theme.accent();
        float[] hsb = java.awt.Color.RGBtoHSB((accent >> 16) & 0xFF, (accent >> 8) & 0xFF, accent & 0xFF, null);
        float h = hsb[0] + c.ordinal() / (float) Category.values().length;
        return 0xFF000000 | java.awt.Color.HSBtoRGB(h - (float) Math.floor(h), 0.55f, 0.95f);
    }

    private void renderSidebar(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        int s = Render2D.scale();
        int y = cy + 6;
        String group = null;
        for (Nav n : navs) {
            if (n.group != null && !n.group.equals(group)) {
                group = n.group;
                y += NAV_GAP;
                Text.draw(g, group.toUpperCase(Locale.ROOT), wx + 12, y + (NAV_HEAD - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
                y += NAV_HEAD;
            }
            boolean current = n == nav && !searching() || hudMode && n.kind == Kind.HUD;
            boolean over = Page.inside(mouseX, mouseY, wx + 4, y, SIDE - 8, NAV_ROW);
            float[] h = anim(n);
            h[0] = Anim.approach(h[0], current ? 1 : 0, dt, 14);
            h[1] = Anim.approach(Float.isNaN(h[1]) ? 0 : h[1], over ? 1 : 0, dt, 18);
            if (over) g.requestCursor(CursorTypes.POINTING_HAND);

            Render2D.pixels(g);
            if (h[1] > 0.01f) Render2D.rect(g, (wx + 5) * s, y * s, (SIDE - 10) * s, NAV_ROW * s, 3 * s, Theme.alpha(0xFFFFFF, 9 * h[1] * a));
            if (h[0] > 0.01f) {
                Render2D.rect(g, (wx + 5) * s, y * s, (SIDE - 10) * s, NAV_ROW * s, 3 * s, Theme.alpha(Theme.accent(), 38 * h[0] * a));
                Render2D.rect(g, (wx + 5) * s, (y + 3) * s, Math.max(2, s), (NAV_ROW - 6) * s, s, Theme.alpha(Theme.accent(), 255 * h[0] * a));
            }
            // A marker for each: a star, a dot that glows when on, or a category's own colour.
            int mx = (wx + 16) * s;
            int my = (y + NAV_ROW / 2) * s;
            switch (n.kind) {
                case FAVOURITES -> Widgets.star(g, mx, my, 3.6 * s, Theme.fade(GOLD, a));
                case ACTIVE -> {
                    Render2D.glow(g, mx - 2 * s, my - 2 * s, 4 * s, 4 * s, 2 * s, 4 * s, Theme.alpha(Theme.accent(), 120 * a));
                    Render2D.rect(g, mx - 2 * s, my - 2 * s, 4 * s, 4 * s, 2 * s, Theme.fade(Theme.accent(), a));
                }
                case CATEGORY -> {
                    int c = hue(n.category);
                    if (h[0] > 0.01f) Render2D.glow(g, mx - 2 * s, my - 2 * s, 4 * s, 4 * s, s, 4 * s, Theme.alpha(c, 150 * h[0] * a));
                    Render2D.rect(g, mx - 2 * s, my - 2 * s, 4 * s, 4 * s, s, Theme.fade(c, a));
                }
                default -> Render2D.rect(g, mx - s, my - s, 2 * s, 2 * s, s, Theme.fade(Theme.TEXT_FAINT, a));
            }
            Render2D.end(g);

            int textY = y + (NAV_ROW - Text.CAP) / 2;
            Text.draw(g, n.label, wx + 24, textY, Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, Math.max(h[0], h[1])), a));
            String count = count(n);
            if (count != null) {
                int countW = Text.width(count);
                int countX = wx + SIDE - 11 - countW;
                // Somewhere with modules on gets its count in an accent pill, so you can see at a glance where things are on.
                boolean lit = n.kind == Kind.CATEGORY && count.contains("/") || n.kind == Kind.ACTIVE && !count.equals("0");
                if (lit) {
                    Render2D.pixels(g);
                    Render2D.rect(g, (countX - 4) * s, (y + 3) * s, (countW + 8) * s, (NAV_ROW - 6) * s, 4 * s, Theme.alpha(Theme.accent(), 42 * a));
                    Render2D.end(g);
                }
                Text.draw(g, count, countX, textY, Theme.fade(lit ? Theme.accentText() : Theme.TEXT_FAINT, a));
            }
            y += NAV_ROW;
        }
    }

    /** "3", or "2/14" for a category with some on. */
    private static String count(Nav n) {
        return switch (n.kind) {
            case FAVOURITES -> Integer.toString(FAVOURITES.size());
            case ACTIVE -> Long.toString(Modules.getAll().stream().filter(Module::isEnabled).count());
            case CATEGORY -> {
                List<Module> all = Modules.get(n.category);
                long on = all.stream().filter(Module::isEnabled).count();
                yield on > 0 ? on + "/" + all.size() : Integer.toString(all.size());
            }
            default -> null;
        };
    }

    private Nav navAt(double mouseX, double mouseY) {
        int y = cy + 6;
        String group = null;
        for (Nav n : navs) {
            if (n.group != null && !n.group.equals(group)) {
                group = n.group;
                y += NAV_GAP + NAV_HEAD;
            }
            if (Page.inside(mouseX, mouseY, wx + 4, y, SIDE - 8, NAV_ROW)) return n;
            y += NAV_ROW;
        }
        return null;
    }

    private void go(Nav n) {
        search.clear();
        search.setFocused(false);
        matched = null;
        SettingsList.stopListening();
        if (n.kind == Kind.HUD) {
            hudMode = true;
            hudPage.shownFor = 0;
            hudPage.onShow();
            return;
        }
        nav = n;
        lastNav = n.key;
        listScroll = 0;
        cursor = -1;
        if (n.page != null) {
            n.page.shownFor = 0;
            n.page.onShow();
        }
    }

    // ---- The middle: modules, or what search found

    private List<Row> rows() {
        String key = (searching() ? "?" + search.text().strip().toLowerCase(Locale.ROOT) : nav.key) + "|" + FAVOURITES.size()
            + "|" + (nav.kind == Kind.ACTIVE ? Modules.enabledCount() : 0);
        if (!key.equals(rowsFor)) {
            rowsFor = key;
            if (searching()) {
                rows = search(search.text().strip().toLowerCase(Locale.ROOT));
                // Like a command palette: the best match is picked as you type, Enter takes it.
                cursor = rows.isEmpty() ? -1 : 0;
                listScroll = 0;
                if (!rows.isEmpty() && rows.getFirst().module != null) select(rows.getFirst().module, rows.getFirst().setting);
            } else {
                rows = modules(nav);
                cursor = Math.min(cursor, rows.size() - 1);
            }
        }
        return rows;
    }

    private static List<Row> modules(Nav n) {
        List<Module> list = switch (n.kind) {
            case FAVOURITES -> Modules.getAll().stream().filter(m -> FAVOURITES.contains(m.name)).toList();
            case ACTIVE -> Modules.getAll().stream().filter(Module::isEnabled).toList();
            case CATEGORY -> Modules.get(n.category);
            default -> List.of();
        };
        return list.stream().sorted(Comparator.comparing(m -> m.name, String.CASE_INSENSITIVE_ORDER))
            .map(m -> new Row(m, null, null, null)).toList();
    }

    /**
     * Everything that matches, best first: names that start with it, then names with it in (or
     * whose initials it is, so "ka" finds Kill Aura), then modules with a setting called it, then
     * modules that mention it, with the menu's pages and Look & feel settings mixed in.
     */
    private List<Row> search(String q) {
        record Scored(Row row, int score, String name) {
        }
        List<Scored> found = new ArrayList<>();
        for (Module m : Modules.getAll()) {
            String name = m.name.toLowerCase(Locale.ROOT);
            if (name.startsWith(q)) {
                found.add(new Scored(new Row(m, null, null, null), 0, m.name));
            } else if (wordStarts(name, q) || initials(name).startsWith(q.replace(" ", ""))) {
                found.add(new Scored(new Row(m, null, null, null), 1, m.name));
            } else if (name.contains(q)) {
                // Mid-word ("esp" in Auto Respawn) after whole-word matches (Block ESP).
                found.add(new Scored(new Row(m, null, null, null), 2, m.name));
            } else {
                Setting<?> hit = null;
                for (Setting<?> st : m.settings()) {
                    if (!COMMON.contains(st.name) && st.name.toLowerCase(Locale.ROOT).contains(q)) {
                        hit = st;
                        break;
                    }
                }
                if (hit != null) found.add(new Scored(new Row(m, null, "Setting: " + hit.name, hit), 3, m.name));
                else if (m.description.toLowerCase(Locale.ROOT).contains(q)) found.add(new Scored(new Row(m, null, null, null), 4, m.name));
            }
        }
        for (Nav n : navs) {
            String label = n.label.toLowerCase(Locale.ROOT);
            if (n.kind.ordinal() >= Kind.HUD.ordinal() && label.contains(q)) {
                found.add(new Scored(new Row(null, n, "Open " + n.label, null), wordStarts(label, q) ? 1 : 2, n.label));
            }
        }
        Nav lookNav = navs.stream().filter(n -> n.kind == Kind.LOOK).findFirst().orElseThrow();
        for (Setting<?> st : GuiSettings.ALL) {
            if (st.name.toLowerCase(Locale.ROOT).contains(q)) found.add(new Scored(new Row(null, lookNav, "Look & feel", st), 3, st.name));
        }
        found.sort(Comparator.comparingInt(Scored::score).thenComparing(Scored::name, String.CASE_INSENSITIVE_ORDER));
        return found.stream().map(Scored::row).toList();
    }

    /** Whether a word of {@code name} (after a space, & or the start) begins with {@code q}. */
    private static boolean wordStarts(String name, String q) {
        for (int i = name.indexOf(q); i >= 0; i = name.indexOf(q, i + 1)) {
            if (i == 0 || name.charAt(i - 1) == ' ' || name.charAt(i - 1) == '&') return true;
        }
        return false;
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String word : name.split("[\\s&]+")) {
            if (!word.isEmpty()) sb.append(word.charAt(0));
        }
        return sb.toString();
    }

    private int listX() {
        return cx;
    }

    private void layoutColumns() {
        listW = Math.max(220, Math.round(cw * 0.47f));
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        layoutColumns();
        List<Row> list = rows();
        int x = listX();
        int s = Render2D.scale();

        // Heading: what this is, big, and how many beside it.
        String title = searching() ? "Results" : nav.kind == Kind.CATEGORY ? nav.category.title : nav.label;
        int titleY = cy + (LIST_HEAD - bigCap()) / 2 + 1;
        drawBig(g, fit(title, listW - 90), x + 12, titleY, Theme.fade(Theme.TEXT, a));
        String meta = list.size() + (searching() ? " result" : " module") + (list.size() == 1 ? "" : "s");
        Text.draw(g, meta, x + listW - 12 - Text.width(meta), titleY + bigCap() - Text.CAP, Theme.fade(Theme.TEXT_FAINT, a));

        int top = cy + LIST_HEAD;
        int height = ch - LIST_HEAD;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, list.size() * ROW + 6 - height)));
        boolean overList = Page.inside(mouseX, mouseY, x, top, listW, height);

        g.enableScissor(x, top, x + listW, top + height);
        int y = top - Math.round(listScroll);
        for (int i = 0; i < list.size(); i++) {
            if (y + ROW > top && y < top + height) row(g, list.get(i), i, x + 6, y, listW - 12, overList ? mouseX : -1, mouseY, dt, a);
            y += ROW;
        }
        g.disableScissor();

        if (list.isEmpty()) {
            // An empty list says why, and what fills it.
            String head = searching() ? "No matches" : nav.kind == Kind.FAVOURITES ? "No favourites yet" : nav.kind == Kind.ACTIVE ? "Nothing's on" : "Empty";
            String hint = searching() ? "Nothing called \"" + search.text().strip() + "\". Try fewer letters."
                : nav.kind == Kind.FAVOURITES ? "Click the star on any module to keep it here."
                : nav.kind == Kind.ACTIVE ? "Switch a module on and it shows up here." : "No modules here.";
            hint = fit(hint, listW - 24);
            int ey = top + (height - bigCap() - 14) / 2 - 12;
            drawBig(g, head, x + (listW - bigWidth(head)) / 2, ey, Theme.fade(Theme.TEXT_DIM, a));
            Text.draw(g, hint, x + (listW - Text.width(hint)) / 2, ey + bigCap() + 8, Theme.fade(Theme.TEXT_FAINT, a));
        }
        int content = list.size() * ROW + 6;
        if (content > height) {
            int barH = Math.max(14, height * height / content);
            int barY = top + Math.round((height - barH) * listScroll / (content - height));
            Render2D.pixels(g);
            Render2D.rect(g, (x + listW - 4) * s, barY * s, 2 * s, barH * s, s, Theme.alpha(0xFFFFFF, 34 * a));
            Render2D.end(g);
        }
        Render2D.pixels(g);
        int line = Math.max(1, s / 2);
        g.fill((x + listW) * s - line, cy * s, (x + listW) * s, (cy + ch) * s, Theme.fade(Theme.BORDER, a));
        Render2D.end(g);
    }

    private void row(GuiGraphics g, Row row, int index, int x, int y, int w, int mouseX, int mouseY, float dt, float a) {
        int s = Render2D.scale();
        Module m = row.module;
        boolean current = m != null && m == selected || index == cursor;
        boolean over = Page.inside(mouseX, mouseY, x, y + 1, w, ROW - 2);
        // Keyed by the row, not what it points at: a page's row mustn't share the sidebar's animation.
        float[] h = anim(row);
        h[0] = Anim.approach(h[0], over ? 1 : 0, dt, 18);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        Render2D.pixels(g);
        if (current) {
            Render2D.rect(g, x * s, (y + 1) * s, w * s, (ROW - 2) * s, 3 * s, Theme.alpha(Theme.accent(), 34 * a));
            Render2D.rect(g, x * s, (y + 5) * s, Math.max(2, s), (ROW - 10) * s, s, Theme.fade(Theme.accent(), a));
        } else if (h[0] > 0.01f) {
            Render2D.rect(g, x * s, (y + 1) * s, w * s, (ROW - 2) * s, 3 * s, Theme.alpha(0xFFFFFF, 9 * h[0] * a));
        }
        Render2D.end(g);

        int right = x + w - 8;
        if (m != null) {
            float target = m.isEnabled() ? 1 : 0;
            h[1] = Float.isNaN(h[1]) ? target : Anim.approach(h[1], target, dt, 14);
            int toggleX = right - Widgets.TOGGLE_W;
            Widgets.toggle(g, toggleX, y + (ROW - Widgets.TOGGLE_H) / 2, h[1], a);
            right = toggleX - 8;
            boolean fav = FAVOURITES.contains(m.name);
            boolean overStar = Page.inside(mouseX, mouseY, right - 9, y, 11, ROW);
            Render2D.pixels(g);
            Widgets.star(g, (right - 4) * s, (y + ROW / 2.0) * s, 3.8 * s,
                Theme.fade(fav ? GOLD : overStar ? Theme.TEXT_DIM : Theme.mix(Theme.TEXT_FAINT, 0xFF13161C, 0.35f), a));
            Render2D.end(g);
            right -= 14;
            if (m.bind().isBound()) {
                String key = m.bind().display();
                int kw = Text.width(key) + 6;
                right -= kw;
                Render2D.pixels(g);
                Render2D.rect(g, right * s, (y + 8) * s, kw * s, (Text.CAP + 4) * s, 2 * s, Theme.fade(Theme.TRACK, a));
                Render2D.end(g);
                Text.draw(g, key, right + 3, y + 10, Theme.fade(Theme.TEXT_DIM, a));
                right -= 6;
            }
            // On: a small glowing dot before the name, so what's running stands out down the list.
            if (h[1] > 0.02f) {
                int dot = Math.max(2, Math.round(2.6f * s));
                int dx = (x + 9) * s - dot / 2;
                int dy = (y + 8) * s - dot / 2 + s / 2;
                Render2D.pixels(g);
                Render2D.glow(g, dx, dy, dot, dot, dot / 2, 4 * s, Theme.alpha(Theme.accent(), 150 * h[1] * a));
                Render2D.rect(g, dx, dy, dot, dot, dot / 2, Theme.fade(Theme.accentText(), h[1] * a));
                Render2D.end(g);
            }
            int textX = x + 15;
            int nameColor = m.anticheatDownOnly() ? Theme.DANGER : m.isEnabled() ? Theme.TEXT : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h[0]);
            Text.draw(g, fit(m.name, right - textX - 2), textX, y + 5, Theme.fade(nameColor, a));
            int afterName = textX + Text.width(m.name) + 6;
            if ((searching() || nav.kind != Kind.CATEGORY) && afterName + Text.width(m.category.title) < right) {
                Text.draw(g, m.category.title, afterName, y + 5, Theme.fade(Theme.mix(hue(m.category), Theme.TEXT_FAINT, 0.45f), a));
            }
            String second = row.reason != null ? row.reason : m.description;
            Text.draw(g, fit(second, right - textX - 2), textX, y + 15, Theme.fade(row.reason != null ? Theme.accentText() : Theme.TEXT_FAINT, a));
        } else {
            String label = row.setting != null ? row.setting.name : row.nav.label;
            Text.draw(g, label, x + 15, y + 5, Theme.fade(Theme.TEXT, a));
            Text.draw(g, fit(row.reason, w - 37), x + 15, y + 15, Theme.fade(Theme.accentText(), a));
            Render2D.pixels(g);
            Render2D.chevron(g, (x + w - 10) * s, (y + ROW / 2) * s, (float) (-Math.PI / 2), Theme.fade(Theme.TEXT_DIM, a));
            Render2D.end(g);
        }
    }

    // ---- The right: the picked module's settings

    private void renderInspector(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        int x = listX() + listW + 12;
        int w = cx + cw - x - 12;
        int s = Render2D.scale();
        if (selected == null) {
            emptyInspector(g, x, w, a);
            return;
        }
        if (inspectorFor != selected) {
            inspectorFor = selected;
            inspector = new SettingsList(selected.settings());
            inspectScroll = 0;
        }
        float[] h = anim(inspector);
        float target = selected.isEnabled() ? 1 : 0;
        h[1] = Float.isNaN(h[1]) ? target : Anim.approach(h[1], target, dt, 14);

        // A card for the module: tinted in its category's colour, with an edge of it down the left.
        int c = hue(selected.category);
        List<String> about = wrap(selected.description, w - 20, 3);
        int cardY = cy + 8;
        int cardH = 9 + bigCap() + 7 + 11 + 7 + (selected.anticheatDownOnly() ? 11 : 0) + about.size() * 10 + 5;
        Render2D.pixels(g);
        Render2D.rect(g, x * s, cardY * s, w * s, cardH * s, 4 * s, Theme.alpha(c, 16 * a));
        Render2D.rect(g, x * s, (cardY + 4) * s, Math.max(2, s), (cardH - 8) * s, s, Theme.fade(c, a));
        Render2D.glow(g, x * s, (cardY + 4) * s, Math.max(2, s), (cardH - 8) * s, 0, 5 * s, Theme.alpha(c, 70 * Theme.glow() * a));
        Render2D.end(g);

        int y = cardY + 9;
        drawBig(g, fit(selected.name, w - 44), x + 11, y, Theme.fade(selected.anticheatDownOnly() ? Theme.DANGER : Theme.TEXT, a));
        toggleX = x + w - 10 - Widgets.TOGGLE_W;
        toggleY = y + (bigCap() - Widgets.TOGGLE_H) / 2;
        if (Page.inside(mouseX, mouseY, toggleX - 2, toggleY - 3, Widgets.TOGGLE_W + 4, Widgets.TOGGLE_H + 6)) g.requestCursor(CursorTypes.POINTING_HAND);
        Widgets.toggle(g, toggleX, toggleY, h[1], a);
        y += bigCap() + 7;

        // Chips: its category, and its key if it has one.
        int chipX = x + 11;
        chipX = chip(g, selected.category.title, chipX, y, Theme.alpha(c, 46 * a), Theme.fade(Theme.mix(c, 0xFFFFFFFF, 0.35f), a));
        if (selected.bind().isBound()) chip(g, "Key  " + selected.bind().display(), chipX + 4, y, Theme.fade(Theme.TRACK, a), Theme.fade(Theme.TEXT_DIM, a));
        y += 11 + 7;
        if (selected.anticheatDownOnly()) {
            Text.draw(g, "Only for while the anticheat is down", x + 11, y, Theme.fade(Theme.DANGER, a));
            y += 11;
        }
        for (String line : about) {
            Text.draw(g, line, x + 11, y, Theme.fade(Theme.TEXT_DIM, a));
            y += 10;
        }

        y = cardY + cardH + 8;
        Text.draw(g, "SETTINGS", x + 2, y, Theme.fade(Theme.TEXT_FAINT, a));
        y += Text.CAP + 5;

        settingsTop = y;
        int height = cy + ch - 6 - y;
        inspectScroll = Math.max(0, Math.min(inspectScroll, Math.max(0, inspector.height() - height)));
        boolean inside = Page.inside(mouseX, mouseY, x, y, w, height);
        g.enableScissor(x - 2, y, x + w + 2, y + height);
        int listY = y - Math.round(inspectScroll);
        if (matched != null && selected.settings().contains(matched)) highlight(g, selected.settings(), matched, x, listY, w, a);
        inspector.render(g, x, listY, w, mouseX, mouseY, dt, a, inside && grabbed == null || grabbed == inspector);
        g.disableScissor();
    }

    /** A small rounded tag. @return where the next one can go */
    private static int chip(GuiGraphics g, String text, int x, int y, int fill, int color) {
        int s = Render2D.scale();
        int w = Text.width(text) + 10;
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, 11 * s, 3 * s, fill);
        Render2D.end(g);
        Text.draw(g, text, x + 5, y + 2, color);
        return x + w;
    }

    /** Nothing picked yet: a few pointers instead of an empty column. */
    private void emptyInspector(GuiGraphics g, int x, int w, float a) {
        int s = Render2D.scale();
        String head = "Pick a module";
        int y = cy + ch / 2 - 34;
        drawBig(g, head, x + (w - bigWidth(head)) / 2, y, Theme.fade(Theme.TEXT_DIM, a));
        y += bigCap() + 14;
        String[] tips = {"Type anywhere to search everything", "Right click a module to switch it", "Star the ones you use most"};
        int widest = 0;
        for (String tip : tips) widest = Math.max(widest, Text.width(tip));
        int tx = x + (w - widest - 8) / 2;
        for (String tip : tips) {
            Render2D.pixels(g);
            int dot = Math.max(2, 2 * s);
            Render2D.rect(g, tx * s, (y + 2) * s + s / 2, dot, dot, dot / 2, Theme.fade(Theme.accent(), a));
            Render2D.end(g);
            Text.draw(g, tip, tx + 8, y, Theme.fade(Theme.TEXT_FAINT, a));
            y += 13;
        }
    }

    /** A soft accent band behind the setting search found, so the eye lands on it. */
    private static void highlight(GuiGraphics g, List<Setting<?>> settings, Setting<?> target, int x, int y, int w, float a) {
        int s = Render2D.scale();
        int ry = y;
        for (Setting<?> st : settings) {
            if (st == target) {
                float pulse = 0.6f + 0.4f * (float) Math.sin(System.nanoTime() / 1e9 * 4);
                Render2D.pixels(g);
                Render2D.rect(g, (x - 2) * s, ry * s, (w + 4) * s, SettingsList.heightOf(st) * s, 3 * s, Theme.alpha(Theme.accent(), 46 * pulse * a));
                Render2D.end(g);
                return;
            }
            ry += SettingsList.heightOf(st);
        }
    }

    /** Scrolls the settings so {@code target} is near the top. */
    private void scrollTo(List<Setting<?>> settings, Setting<?> target) {
        int ry = 0;
        for (Setting<?> st : settings) {
            if (st == target) break;
            ry += SettingsList.heightOf(st);
        }
        inspectScroll = Math.max(0, ry - 20);
    }

    // ---- Look & feel

    private void renderLook(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        int w = 260;
        int x = cx + (cw - w) / 2;
        int y = cy + 12;
        drawBig(g, "Look & feel", x, y, Theme.fade(Theme.TEXT, a));
        String reset = "Reset all";
        int rx = x + w - Text.width(reset);
        int ry = resetY();
        boolean overReset = Page.inside(mouseX, mouseY, rx - 3, ry - 3, Text.width(reset) + 6, 14);
        if (overReset) g.requestCursor(CursorTypes.POINTING_HAND);
        Text.draw(g, reset, rx, ry, Theme.fade(overReset ? Theme.accentText() : Theme.TEXT_FAINT, a));
        String tour = "Replay tour";
        int tx = tourLinkX();
        boolean overTour = Page.inside(mouseX, mouseY, tx - 3, ry - 3, Text.width(tour) + 6, 14);
        if (overTour) g.requestCursor(CursorTypes.POINTING_HAND);
        Text.draw(g, tour, tx, ry, Theme.fade(overTour ? Theme.accentText() : Theme.TEXT_FAINT, a));
        y += bigCap() + 8;
        for (String line : wrap("Right click a value to put it back. Font changes the HUD and the classic menu; this one stays smooth. "
            + "Beta UI off goes back to the classic menu.", w, 3)) {
            Text.draw(g, line, x, y, Theme.fade(Theme.TEXT_FAINT, a));
            y += 10;
        }
        y += 6;
        settingsTop = y;
        int height = cy + ch - 6 - y;
        lookScroll = Math.max(0, Math.min(lookScroll, Math.max(0, look.height() - height)));
        g.enableScissor(x - 2, y, x + w + 2, y + height);
        int listY = y - Math.round(lookScroll);
        if (matched != null && GuiSettings.ALL.contains(matched)) highlight(g, GuiSettings.ALL, matched, x, listY, w, a);
        look.render(g, x, listY, w, mouseX, mouseY, dt, a, Page.inside(mouseX, mouseY, x, y, w, height) && grabbed == null || grabbed == look);
        g.disableScissor();
    }

    private int lookX() {
        return cx + (cw - 260) / 2;
    }

    private int tourLinkX() {
        return lookX() + 260 - Text.width("Reset all") - 14 - Text.width("Replay tour");
    }

    /** Reset all sits level with the bottom of the Look & feel title. */
    private int resetY() {
        return cy + 12 + bigCap() - Text.CAP;
    }

    // ---- The menu's other pages, inside the window

    /** Pages lay themselves out from the top of a full screen; this puts that top inside the window. */
    private int pageOffsetY() {
        return cy + 4 - Page.TOP;
    }

    private void renderPage(GuiGraphics g, int mouseX, int mouseY, float dt, float a) {
        Page page = nav.page;
        page.resize(cw, ch + Page.TOP - 4);
        page.shownFor += dt;
        g.enableScissor(cx, cy, cx + cw, cy + ch);
        g.pose().pushMatrix();
        g.pose().translate(cx, pageOffsetY());
        page.render(g, mouseX - cx, mouseY - pageOffsetY(), dt, a);
        g.pose().popMatrix();
        g.disableScissor();
    }

    // ---- Footer

    /** Hints as keycaps: the key in a little cap, what it does beside it. As many as fit. */
    private void renderFooter(GuiGraphics g, float a) {
        int s = Render2D.scale();
        int y = wy + wh - FOOT + (FOOT - Text.CAP) / 2;
        String[][] hints = nav.kind == Kind.PAGE || nav.kind == Kind.LOOK
            ? new String[][] {{"Type", "to search everything"}, {"Esc", "closes"}}
            : new String[][] {{"Type", "to search"}, {"Up/Down", "picks"}, {"Enter", "switches on/off"}, {"Esc", "closes"}};
        int x = wx + 12;
        int limit = wx + ww - 24 - Text.width(version);
        for (String[] hint : hints) {
            int kw = Text.width(hint[0]) + 8;
            if (x + kw + 4 + Text.width(hint[1]) > limit) break;
            Render2D.pixels(g);
            Render2D.rect(g, x * s, (y - 2) * s, kw * s, (Text.CAP + 4) * s, 3 * s, Theme.alpha(0xFFFFFF, 16 * a));
            Render2D.rect(g, x * s, (y + Text.CAP + 1) * s, kw * s, Math.max(1, s / 2), 0, Theme.alpha(0xFFFFFF, 10 * a));
            Render2D.end(g);
            Text.draw(g, hint[0], x + 4, y, Theme.fade(Theme.TEXT_DIM, a));
            x += kw + 4;
            Text.draw(g, hint[1], x, y, Theme.fade(Theme.TEXT_FAINT, a));
            x += Text.width(hint[1]) + 11;
        }
        Text.draw(g, version, wx + ww - 12 - Text.width(version), y, Theme.fade(Theme.TEXT_FAINT, a));
    }

    // ---- Text helpers

    private static String fit(String text, int room) {
        if (room <= 0) return "";
        if (Text.width(text) <= room) return text;
        String cut = text;
        while (!cut.isEmpty() && Text.width(cut + "...") > room) cut = cut.substring(0, cut.length() - 1);
        return cut.stripTrailing() + "...";
    }

    private static List<String> wrap(String text, int room, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (Text.width(next) > room && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
                if (lines.size() == maxLines) break;
            } else {
                line = new StringBuilder(next);
            }
        }
        if (lines.size() < maxLines && !line.isEmpty()) lines.add(line.toString());
        if (lines.size() == maxLines) lines.set(maxLines - 1, fit(lines.get(maxLines - 1), room));
        return lines;
    }

    // ---- Mouse

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return styled(() -> click(event));
    }

    private boolean click(MouseButtonEvent event) {
        if (closing) return true;
        double mx = toUi(event.x());
        double my = toUi(event.y());
        int button = event.button();
        if (tutorial != null) {
            tutorial.mouseClicked(mx, my, button);
            return true;
        }
        pressed = true;
        grabbed = null;
        SettingsList.stopListening();

        if (hudMode) {
            String back = "Back to the menu";
            int bw = Text.width(back) + 20;
            if (button == 0 && Page.inside(mx, my, (uiW - bw) / 2, 8, bw, 16)) {
                leaveHud();
                return true;
            }
            grabbed = hudPage;
            hudPage.mouseClicked(mx, my, button);
            return true;
        }

        // Top bar.
        int closeX = wx + ww - 18;
        if (button == 0 && Page.inside(mx, my, closeX - 4, wy + 6, 16, 18)) {
            onClose();
            return true;
        }
        String active = Modules.getAll().stream().filter(Module::isEnabled).count() + " on";
        int ax = closeX - 14 - Text.width(active);
        if (button == 0 && Page.inside(mx, my, ax - 4, wy + 6, Text.width(active) + 8, 18)) {
            go(navs.get(1));
            return true;
        }
        if (search.mouseClicked(mx, my)) return true;

        Nav n = navAt(mx, my);
        if (n != null) {
            if (button == 0) go(n);
            return true;
        }

        if (searching() || nav.kind == Kind.FAVOURITES || nav.kind == Kind.ACTIVE || nav.kind == Kind.CATEGORY) {
            if (clickList(mx, my, button)) return true;
            if (selected != null && inspector != null) {
                int x = listX() + listW + 12;
                int w = cx + cw - x - 12;
                if (button == 0 && Page.inside(mx, my, toggleX - 2, toggleY - 3, Widgets.TOGGLE_W + 4, Widgets.TOGGLE_H + 6)) {
                    selected.toggle();
                    return true;
                }
                if (Page.inside(mx, my, x, settingsTop, w, cy + ch - 6 - settingsTop) && inspector.mouseClicked(mx, my, button)) {
                    if (inspector.isDragging()) grabbed = inspector;
                    return true;
                }
            }
        } else if (nav.kind == Kind.LOOK) {
            int x = lookX();
            String reset = "Reset all";
            if (button == 0 && Page.inside(mx, my, tourLinkX() - 3, resetY() - 3, Text.width("Replay tour") + 6, 14)) {
                // Every tour starts by picking a menu.
                tutorial = Tutorial.chooser(true, this::chosen, this::tourDone);
                return true;
            }
            if (button == 0 && Page.inside(mx, my, x + 260 - Text.width(reset) - 3, resetY() - 3, Text.width(reset) + 6, 14)) {
                look.resetAll();
                // Resetting the look mustn't throw you out of the menu you're resetting it from.
                GuiSettings.BETA.set(true);
                return true;
            }
            if (Page.inside(mx, my, x, settingsTop, 260, cy + ch - 6 - settingsTop) && look.mouseClicked(mx, my, button)) {
                if (look.isDragging()) grabbed = look;
                return true;
            }
        } else if (nav.kind == Kind.PAGE && Page.inside(mx, my, cx, cy, cw, ch)) {
            grabbed = nav.page;
            nav.page.mouseClicked(mx - cx, my - pageOffsetY(), button);
            return true;
        }
        return true;
    }

    private boolean clickList(double mx, double my, int button) {
        int x = listX();
        int top = cy + LIST_HEAD;
        if (!Page.inside(mx, my, x, top, listW, ch - LIST_HEAD)) return false;
        int index = (int) Math.floor((my - top + listScroll) / ROW);
        List<Row> list = rows();
        if (index < 0 || index >= list.size()) return true;
        Row row = list.get(index);
        cursor = index;
        if (row.module == null) {
            if (button == 0) open(row);
            return true;
        }
        Module m = row.module;
        int right = x + 6 + listW - 12 - 8;
        int toggleX = right - Widgets.TOGGLE_W;
        if (button == 1 || button == 0 && mx >= toggleX - 3) {
            m.toggle();
        } else if (button == 0 && mx >= toggleX - 8 - 11) {
            if (!FAVOURITES.remove(m.name)) FAVOURITES.add(m.name);
        } else if (button == 0) {
            select(m, row.setting);
        }
        return true;
    }

    private void select(Module m, Setting<?> setting) {
        selected = m;
        lastSelected = m;
        matched = setting;
        if (inspectorFor != m) {
            inspectorFor = m;
            inspector = new SettingsList(m.settings());
        }
        inspectScroll = 0;
        if (setting != null) scrollTo(m.settings(), setting);
    }

    /** A page or Look & feel setting search found. */
    private void open(Row row) {
        Setting<?> setting = row.setting;
        go(row.nav);
        matched = setting;
        if (setting != null) {
            int ry = 0;
            for (Setting<?> st : GuiSettings.ALL) {
                if (st == setting) break;
                ry += SettingsList.heightOf(st);
            }
            lookScroll = Math.max(0, ry - 20);
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressed) {
            pressed = false;
            if (grabbed instanceof SettingsList list) list.mouseReleased();
            else if (grabbed instanceof Page page) page.mouseReleased(event.button());
            grabbed = null;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return styled(() -> drag(event));
    }

    private boolean drag(MouseButtonEvent event) {
        double mx = toUi(event.x());
        double my = toUi(event.y());
        if (grabbed instanceof SettingsList list) list.mouseDragged(mx);
        else if (grabbed == hudPage) hudPage.mouseDragged(mx, my);
        else if (grabbed instanceof Page page) page.mouseDragged(mx - cx, my - pageOffsetY());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return styled(() -> scroll(mouseX, mouseY, scrollY));
    }

    private boolean scroll(double mouseX, double mouseY, double scrollY) {
        if (tutorial != null) return true;
        double mx = toUi(mouseX);
        double my = toUi(mouseY);
        float step = (float) (scrollY * ROW * 2);
        if (hudMode) return hudPage.mouseScrolled(mx, my, scrollY);
        if (searching() || nav.kind == Kind.FAVOURITES || nav.kind == Kind.ACTIVE || nav.kind == Kind.CATEGORY) {
            if (mx < listX() + listW) listScroll -= step;
            else inspectScroll -= step;
        } else if (nav.kind == Kind.LOOK) {
            lookScroll -= step;
        } else if (nav.kind == Kind.PAGE) {
            nav.page.mouseScrolled(mx - cx, my - pageOffsetY(), scrollY);
        }
        return true;
    }

    // ---- Keys

    @Override
    public boolean keyPressed(KeyEvent event) {
        return styled(() -> key(event));
    }

    private boolean key(KeyEvent event) {
        if (tutorial != null) {
            if (!tutorial.keyPressed(event) && CyaniteClientMod.clickGuiKey().matches(event)) onClose();
            return true;
        }
        boolean binding = SettingsList.isListening();
        if (SettingsList.captureKey(event)) {
            // The letter a Bind just took comes again as a typed character: that one isn't a search.
            if (binding) eatKey = event.key();
            return true;
        }
        int key = event.key();
        if (hudMode) {
            if (hudPage.keyPressed(event)) return true;
            if (key == GLFW.GLFW_KEY_ESCAPE || CyaniteClientMod.clickGuiKey().matches(event)) {
                leaveHud();
                return true;
            }
            return true;
        }
        if (nav.kind == Kind.PAGE && !searching() && !search.isFocused() && nav.page.keyPressed(event)) return true;

        if (key == GLFW.GLFW_KEY_F && event.hasControlDown()) {
            search.setFocused(true);
            return true;
        }
        boolean listShown = searching() || nav.kind == Kind.FAVOURITES || nav.kind == Kind.ACTIVE || nav.kind == Kind.CATEGORY;
        if (listShown && (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP)) {
            List<Row> list = rows();
            if (list.isEmpty()) return true;
            cursor = Math.max(0, Math.min(list.size() - 1, cursor + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
            Row row = list.get(cursor);
            if (row.module != null) select(row.module, row.setting);
            int top = cursor * ROW;
            int visible = ch - LIST_HEAD;
            if (top < listScroll) listScroll = top;
            else if (top + ROW > listScroll + visible) listScroll = top + ROW - visible;
            return true;
        }
        if (listShown && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            List<Row> list = rows();
            if (cursor < 0 && !list.isEmpty()) cursor = 0;
            if (cursor >= 0 && cursor < list.size()) {
                Row row = list.get(cursor);
                if (row.module != null) {
                    row.module.toggle();
                    if (selected != row.module) select(row.module, row.setting);
                } else {
                    open(row);
                }
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            // One Esc clears a search, the next closes: never a third just to leave the box.
            if (searching()) {
                search.clear();
                search.setFocused(false);
                matched = null;
            } else {
                onClose();
            }
            return true;
        }
        // The menu key closes it even from the search box, unless it's a key that types (rebound to a letter).
        boolean types = key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_GRAVE_ACCENT;
        if (CyaniteClientMod.clickGuiKey().matches(event) && !SettingsList.isEditing() && !(search.isFocused() && types)) {
            onClose();
            return true;
        }
        if (search.keyPressed(event)) return true;
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return styled(() -> typed(event));
    }

    private boolean typed(CharacterEvent event) {
        if (tutorial != null) return true;
        if (eatKey != 0) {
            int eaten = eatKey;
            eatKey = 0;
            if (Character.toUpperCase(event.codepoint()) == eaten) return true;
        }
        if (SettingsList.captureChar(event)) return true;
        if (hudMode) return hudPage.charTyped(event);
        if (nav.kind == Kind.PAGE && nav.page.isTyping()) return nav.page.charTyped(event);
        // Typing anywhere searches.
        if (!search.isFocused()) search.setFocused(true);
        return search.charTyped(event);
    }

    private void leaveHud() {
        hudMode = false;
        hudPage.mouseReleased(0);
        Hud.editing = false;
    }

    @Override
    public void onClose() {
        closing = true;
    }

    @Override
    public void removed() {
        Hud.editing = false;
        SettingsList.stopListening();
        Render2D.setScale(0);
        Config.save();
    }
}
