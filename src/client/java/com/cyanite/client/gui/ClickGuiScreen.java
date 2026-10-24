package com.cyanite.client.gui;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.config.Config;
import com.cyanite.client.donut.DonutSmp;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.hud.Hud;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Modules;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The click GUI. It lays itself out in its own units rather than Minecraft's GUI scale,
 * since at the usual auto scale six windows don't fit side by side.
 */
public class ClickGuiScreen extends Screen {
    private static final float OPEN_TIME = 0.45f;
    private static final float CLOSE_TIME = 0.16f;
    private static int lastTab;
    private static boolean tutorialSeen;

    private final String version = FabricLoader.getInstance().getModContainer("cyanite_client")
        .map(mod -> "v" + mod.getMetadata().getVersion().getFriendlyString())
        .orElse("");

    private final Page[] pages = {new ModulesPage(), new HudPage(), new GuiPage(), new ProfilesPage(), new DonutPage(), new AccountsPage(), new AreaMinerPage(), new MacrosPage()};
    private final TabBar tabs = new TabBar("Modules", "HUD", "GUI", "Profiles", DonutSmp.NAME, "Accounts", "Area Miner", "Macros");
    private final Screen parent;

    private int unit = 1;
    private int uiWidth;
    private int uiHeight;

    private long lastFrame;
    private float progress;
    private boolean closing;
    private boolean pressed;
    private Tutorial tutorial;

    public ClickGuiScreen() {
        this(null, lastTab);
    }

    /** Opened from a menu screen (no world): closing goes back to {@code parent}. */
    public ClickGuiScreen(Screen parent) {
        this(parent, lastTab);
    }

    /** Opens on a given tab, going back to {@code parent} when closed (null for the game). */
    public ClickGuiScreen(Screen parent, int tab) {
        super(Component.literal("Cyanite Client"));
        this.parent = parent;
        tabs.select(tab);
        ((GuiPage) pages[2]).onTutorial = this::startTutorial;
    }

    /** The menu: the Beta UI when that's switched on in Look & feel, the classic one otherwise. */
    public static Screen open(Screen parent) {
        return GuiSettings.BETA.get() ? new BetaGuiScreen(parent) : new ClickGuiScreen(parent);
    }

    public static boolean tutorialSeen() {
        return tutorialSeen;
    }

    public static void setTutorialSeen(boolean seen) {
        tutorialSeen = seen;
    }

    /** The Beta UI's chooser picked this menu: open with its tour. */
    private static boolean tourNext;

    static void tourNext() {
        tourNext = true;
    }

    /** Every tour starts by picking a menu. */
    private void startTutorial() {
        tutorial = Tutorial.chooser(false, this::chosen, this::tutorialDone);
    }

    private void chosen(boolean beta) {
        if (beta) {
            // render() sees the switch and hands over to the Beta UI, which starts its own tour.
            BetaGuiScreen.tourNext();
            GuiSettings.BETA.set(true);
        } else {
            tutorial = new Tutorial(tutorialSteps(), this::selectTab, this::tutorialDone);
        }
    }

    private void tutorialDone() {
        tutorialSeen = true;
        Config.save();
    }

    private List<Tutorial.Step> tutorialSteps() {
        return List.of(
            new Tutorial.Step(0, "Tabs", "Everything lives in these tabs: your modules, the HUD, how this menu looks, your profiles and the Donut SMP tools.", tabs::bounds),
            new Tutorial.Step(0, "Categories", "Modules are grouped into windows like this one. Drag a window by its title to move it, or click the title to fold it away.", () -> ModulesPage.window(Category.COMBAT)),
            new Tutorial.Step(0, "Turning modules on", "Left click a module to switch it on or off. Right click it to show its settings right underneath.", () -> ModulesPage.firstRow(Category.COMBAT)),
            new Tutorial.Step(0, "Bigger modules", "Modules with lots of settings show a few, then an All settings button that opens the rest in their own window.", () -> ModulesPage.window(Category.RENDER)),
            new Tutorial.Step(0, "Keybinds", "Every module has a Bind setting. Click it and press a key, and that key turns the module on and off while you play. Insert opens this menu.", () -> Rect.EMPTY),
            new Tutorial.Step(1, "The HUD", "Switch the watermark and arraylist on or off and change how they look. While this tab is open you can drag them anywhere on screen.", () -> pages[1].bounds()),
            new Tutorial.Step(2, "Make it yours", "Pick a font, an accent color, the menu size, glow and corner roundness. The Tutorial button up here replays this tour.", () -> pages[2].bounds()),
            new Tutorial.Step(3, "Profiles", "Your setup saves on its own. Save it under a name to switch between setups in one click.", () -> pages[3].bounds()),
            new Tutorial.Step(4, DonutSmp.NAME, "Your server's tools: join in one click, run your saved commands, and load a profile automatically when you join.", () -> pages[4].bounds()),
            new Tutorial.Step(5, "Accounts", "Add Microsoft or cracked accounts and switch between them in one click. Insert opens this menu on the title screen and server list too, so you can switch before joining.", () -> pages[5].bounds()),
            new Tutorial.Step(6, "Area Miner", "Pick an area with two corners or a Litematica schematic, hold your drill and press Start. It digs the whole thing out, lane by lane, and never cuts past the edge.", () -> pages[6].bounds()),
            new Tutorial.Step(7, "Macros", "Build macros out of steps: send commands, walk, look, mine, click menu slots, wait for chat. Share codes with friends and import theirs.", () -> pages[7].bounds()),
            new Tutorial.Step(0, "You're all set", "Press Insert any time to open Cyanite. To try the Beta UI, or replay this tour, use the GUI tab.", () -> Rect.EMPTY)
        );
    }

    public static JsonObject saveLayout() {
        return ModulesPage.saveLayout();
    }

    public static void loadLayout(JsonObject json) {
        ModulesPage.loadLayout(json);
    }

    public boolean isTyping() {
        return SettingsList.isEditing() || page().isTyping();
    }

    private Page page() {
        return pages[tabs.selected()];
    }

    private void selectTab(int index) {
        if (index == tabs.selected()) return;
        page().mouseReleased(0);
        tabs.select(index);
        lastTab = index;
        page().shownFor = 0;
        page().onShow();
    }

    private void updateUnit() {
        Window window = minecraft.getWindow();
        String scale = GuiSettings.SCALE.get();

        if (scale.equals("Auto")) {
            unit = Math.max(1, Math.round(window.getHeight() / 540f));
            while (unit > 1 && window.getWidth() / unit < ModulesPage.rowWidth() + 16) unit--;
        } else {
            unit = scale.charAt(0) - '0';
        }

        uiWidth = window.getWidth() / unit;
        uiHeight = window.getHeight() / unit;
        Render2D.setScale(unit);
    }

    private double toUi(double guiCoord) {
        return guiCoord * minecraft.getWindow().getGuiScale() / unit;
    }

    @Override
    protected void init() {
        updateUnit();
        for (Page page : pages) page.resize(uiWidth, uiHeight);
        page().onShow();
        if (tourNext) {
            tourNext = false;
            tutorial = new Tutorial(tutorialSteps(), this::selectTab, this::tutorialDone);
        } else if (!tutorialSeen && tutorial == null) {
            startTutorial();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float a = Anim.easeOut(progress);
        // Keep the world clear on the HUD tab so elements can be placed against it.
        boolean editingHud = page() instanceof HudPage;
        // Opened from the title screen or server list there's no world behind, so show the panorama.
        if (minecraft.level == null) renderPanorama(g, partialTick);
        if (GuiSettings.BLUR.get() && !editingHud) renderBlurredBackground(g);
        if (editingHud) a *= 0.4f;
        g.fillGradient(0, 0, width, height, Theme.fade(Theme.TINT_TOP, a), Theme.fade(Theme.TINT_BOTTOM, a));
    }

    @Override
    public void render(GuiGraphics g, int guiMouseX, int guiMouseY, float partialTick) {
        // Beta UI switched on from the GUI tab: over to it.
        if (GuiSettings.BETA.get()) {
            if (!BetaGuiScreen.tourPending()) BetaGuiScreen.openOnLook();
            minecraft.setScreen(new BetaGuiScreen(parent));
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
        int realMouseX = (int) toUi(guiMouseX);
        int realMouseY = (int) toUi(guiMouseY);
        // While the tour is up, nothing behind it should react to the mouse.
        int mouseX = tutorial == null ? realMouseX : -10000;
        int mouseY = tutorial == null ? realMouseY : -10000;
        float alpha = Anim.easeOut(progress);

        g.pose().pushMatrix();
        g.pose().scale((float) unit / minecraft.getWindow().getGuiScale());

        Page page = page();
        Hud.editing = page instanceof HudPage;
        page.resize(uiWidth, uiHeight);
        page.shownFor += dt;
        page.render(g, mouseX, mouseY, dt, alpha);
        g.nextStratum();

        tabs.render(g, uiWidth / 2, 8, mouseX, mouseY, dt, alpha);
        // On the HUD tab the footer would read as part of the HUD being edited.
        if (!(page instanceof HudPage)) renderFooter(g, alpha);

        if (tutorial != null) {
            g.nextStratum();
            tutorial.render(g, uiWidth, uiHeight, realMouseX, realMouseY, dt, alpha);
            if (tutorial.isFinished()) tutorial = null;
        }

        g.pose().popMatrix();
    }

    private void renderFooter(GuiGraphics g, float a) {
        if (a < 0.05f) return;

        String name = "Cyanite";
        int nameX = 10;
        int nameY = uiHeight - 16;
        int nameW = Text.width(name);

        Render2D.pixels(g);
        Render2D.glow(g, nameX * unit, nameY * unit, nameW * unit, Text.CAP * unit, 3 * unit, 9 * unit,
            Theme.alpha(Theme.accent(), 70 * Theme.glow() * a));
        Render2D.end(g);

        Text.draw(g, name, nameX, nameY, Theme.fade(Theme.accentText(), a));
        Text.draw(g, version, nameX + nameW + 4, nameY, Theme.fade(Theme.TEXT_FAINT, a));

        if (!Modules.getAll().isEmpty()) {
            String status = Modules.enabledCount() + " active";
            Text.draw(g, status, uiWidth - 10 - Text.width(status), nameY, Theme.fade(Theme.TEXT_DIM, a));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closing) return true;

        double mouseX = toUi(event.x());
        double mouseY = toUi(event.y());

        if (tutorial != null) {
            tutorial.mouseClicked(mouseX, mouseY, event.button());
            return true;
        }

        int tab = tabs.tabAt(mouseX, mouseY);
        if (tab >= 0) {
            if (event.button() == 0) selectTab(tab);
            return true;
        }

        pressed = true;
        SettingsList.stopListening();
        if (page().mouseClicked(mouseX, mouseY, event.button())) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressed) {
            pressed = false;
            page().mouseReleased(event.button());
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (tutorial != null) return true;
        if (pressed) {
            page().mouseDragged(toUi(event.x()), toUi(event.y()));
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tutorial != null) return true;
        if (page().mouseScrolled(toUi(mouseX), toUi(mouseY), scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (tutorial != null && tutorial.keyPressed(event)) return true;
        if (SettingsList.captureKey(event)) return true;
        if (page().keyPressed(event)) return true;

        if (CyaniteClientMod.clickGuiKey().matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (tutorial != null) return true;
        if (SettingsList.captureChar(event)) return true;
        return page().charTyped(event) || super.charTyped(event);
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
