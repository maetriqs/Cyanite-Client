package com.cyanite.client.gui;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.player.InventoryMove;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The category windows, plus a search box beside the tabs. Start typing (or Ctrl+F) to search:
 * every window shows just the modules that match, by name, a setting or what they do, and Enter
 * switches the best match on or off. Esc clears the search, then closes the menu.
 */
final class ModulesPage extends Page {
    private static final int GAP = 8;
    private static final int FIELD_W = 150;

    // Static so positions and collapsed state survive closing the screen.
    private static final List<CategoryWindow> WINDOWS = new ArrayList<>();
    // Module settings windows always sit above the category windows; last is front-most.
    private static final List<SettingsWindow> SETTINGS = new ArrayList<>();
    private static boolean laidOut;

    static {
        for (Category category : Category.values()) {
            WINDOWS.add(new CategoryWindow(category));
        }
    }

    private CategoryWindow active;
    private SettingsWindow activeSettings;

    private final TextField search = new TextField(32, cp -> cp >= 32 && cp != 127);
    private Rect tabs = Rect.EMPTY;
    private Rect field = Rect.EMPTY;
    /** The search, stripped and lower case; empty for none. */
    private String query = "";
    private Module best;
    private int found;

    ModulesPage() {
        search.setPadLeft(17);
    }

    @Override
    void tabBar(Rect bounds) {
        tabs = bounds;
    }

    /** Where the search box is, for the tour. */
    Rect searchBounds() {
        return field;
    }

    /** UI units needed to fit every window side by side. */
    static int rowWidth() {
        return WINDOWS.size() * CategoryWindow.WIDTH + (WINDOWS.size() - 1) * GAP;
    }

    static JsonObject saveLayout() {
        JsonObject json = new JsonObject();
        for (CategoryWindow window : WINDOWS) json.add(window.category.name(), window.save());
        return json;
    }

    static void loadLayout(JsonObject json) {
        List<CategoryWindow> unplaced = new ArrayList<>();
        for (CategoryWindow window : WINDOWS) {
            if (json.get(window.category.name()) instanceof JsonObject saved) {
                window.load(saved);
                laidOut = true;
            } else {
                unplaced.add(window);
            }
        }
        if (!laidOut) return;

        int right = 0;
        for (CategoryWindow window : WINDOWS) {
            if (!unplaced.contains(window)) right = Math.max(right, window.x + CategoryWindow.WIDTH);
        }
        for (CategoryWindow window : unplaced) {
            window.x = right + GAP;
            window.y = TOP;
            right = window.x + CategoryWindow.WIDTH;
        }
    }

    /** Where a category's window sits, for the tutorial. */
    static Rect window(Category category) {
        for (CategoryWindow window : WINDOWS) {
            if (window.category == category) return new Rect(window.x, window.y, CategoryWindow.WIDTH, window.height());
        }
        return Rect.EMPTY;
    }

    /** The first module row of a category's window. */
    static Rect firstRow(Category category) {
        Rect window = window(category);
        if (window.isEmpty()) return window;
        return new Rect(window.x(), window.y() + CategoryWindow.HEADER + 3, CategoryWindow.WIDTH, CategoryWindow.ROW);
    }

    /** Opens (or brings forward) the settings window for a module, near the given spot. */
    static void openSettings(Module module, int nearX, int nearY) {
        for (SettingsWindow window : SETTINGS) {
            if (window.module == module) {
                window.reopen();
                SETTINGS.remove(window);
                SETTINGS.add(window);
                return;
            }
        }
        SETTINGS.add(new SettingsWindow(module, nearX, nearY));
    }

    @Override
    void resize(int width, int height) {
        super.resize(width, height);
        if (laidOut) return;
        laidOut = true;

        int windowX = Math.max(6, (width - rowWidth()) / 2);
        for (CategoryWindow window : WINDOWS) {
            window.x = windowX;
            window.y = TOP;
            windowX += CategoryWindow.WIDTH + GAP;
        }
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        String q = search.text().strip().toLowerCase(Locale.ROOT);
        if (!q.equals(query)) {
            query = q;
            findBest();
        }
        for (CategoryWindow window : WINDOWS) window.filter(query);
        CategoryWindow.best = best;

        SettingsWindow hoveredSettings = settingsAt(mouseX, mouseY);
        CategoryWindow hovered = hoveredSettings == null && activeSettings == null ? windowAt(mouseX, mouseY) : null;

        for (CategoryWindow window : WINDOWS) {
            window.clamp(width, height);

            float appear = alpha;
            if (Anim.enabled()) appear *= Anim.easeOut((shownFor - window.category.ordinal() * 0.035f) / 0.24f);

            int unit = Render2D.scale();
            int slide = Math.round((1 - appear) * -8 * unit);

            g.pose().pushMatrix();
            g.pose().translate(0, slide / (float) unit);
            window.render(g, mouseX, mouseY, dt, appear, window == hovered);
            g.pose().popMatrix();

            // Each window gets its own layer so overlapping text doesn't bleed through.
            g.nextStratum();
        }

        SETTINGS.removeIf(SettingsWindow::isGone);
        for (SettingsWindow window : List.copyOf(SETTINGS)) {
            window.clamp(width, height);
            window.render(g, mouseX, mouseY, dt, alpha, window == hoveredSettings);
            g.nextStratum();
        }

        renderSearch(g, mouseX, mouseY, dt, alpha);
    }

    /** The best match over every category: what Enter switches. Ties go to the name first alphabetically. */
    private void findBest() {
        best = null;
        found = 0;
        if (query.isEmpty()) return;
        int bestScore = Integer.MAX_VALUE;
        for (Module module : Modules.getAll()) {
            ModuleSearch.Hit hit = ModuleSearch.match(module, query);
            if (hit == null) continue;
            found++;
            if (hit.score() < bestScore || hit.score() == bestScore && module.name.compareToIgnoreCase(best.name) < 0) {
                best = module;
                bestScore = hit.score();
            }
        }
    }

    private void renderSearch(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        // Level with the tabs, on whichever side has room; above the footer if neither does.
        int h = TabBar.HEIGHT;
        int y = tabs.isEmpty() ? 8 : tabs.y();
        int right = width - 10 - FIELD_W;
        if (tabs.isEmpty() || right >= tabs.x() + tabs.w() + 8) field = new Rect(right, y, FIELD_W, h);
        else if (10 + FIELD_W + 8 <= tabs.x()) field = new Rect(10, y, FIELD_W, h);
        else field = new Rect((width - FIELD_W) / 2, height - 22 - h, FIELD_W, h);

        float a = alpha * entrance();
        search.render(g, field.x(), field.y(), field.w(), field.h(), "Search modules...", mouseX, mouseY, dt, a);
        Widgets.magnifier(g, field.x() + 9, field.y() + h / 2, Theme.fade(search.isFocused() ? Theme.accentText() : Theme.TEXT_DIM, a), a);

        // How many, inside the box's right end (when the typing leaves room), so nothing hangs
        // below it over the windows. The best match is lit up in its window for Enter.
        if (query.isEmpty()) return;
        String count = best == null ? "no matches" : found + " found";
        int countX = field.x() + field.w() - 6 - Text.width(count);
        if (field.x() + 17 + Text.width(search.text()) + 8 <= countX) {
            Text.draw(g, count, countX, field.y() + (h - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
        }
    }

    @Override
    boolean isTyping() {
        return search.isFocused();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_F && event.hasControlDown()) {
            search.setFocused(true);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            // One Esc clears the search, the next closes: never a third just to leave the box.
            search.setFocused(false);
            if (query.isEmpty()) return false;
            search.clear();
            return true;
        }
        if (!query.isEmpty() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            if (best != null) best.toggle();
            return true;
        }
        if (!search.isFocused()) return false;
        // The menu key still closes the menu from the box, unless it's been rebound to a key that types.
        boolean types = key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_GRAVE_ACCENT;
        if (CyaniteClientMod.clickGuiKey().matches(event) && !types) return false;
        return search.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        if (!search.isFocused()) {
            // Typing anywhere searches, unless Inventory Move has you walking around with the menu open.
            if (Modules.get(InventoryMove.class).walksInClickGui()) return false;
            search.setFocused(true);
        }
        return search.charTyped(event);
    }

    private SettingsWindow settingsAt(double mouseX, double mouseY) {
        if (activeSettings != null) return activeSettings;
        if (active != null) return null;
        for (int i = SETTINGS.size() - 1; i >= 0; i--) {
            if (SETTINGS.get(i).isOver(mouseX, mouseY)) return SETTINGS.get(i);
        }
        return null;
    }

    private CategoryWindow windowAt(double mouseX, double mouseY) {
        if (active != null) return active;
        for (int i = WINDOWS.size() - 1; i >= 0; i--) {
            if (WINDOWS.get(i).isOver(mouseX, mouseY)) return WINDOWS.get(i);
        }
        return null;
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (search.mouseClicked(mouseX, mouseY)) return true;

        for (int i = SETTINGS.size() - 1; i >= 0; i--) {
            SettingsWindow window = SETTINGS.get(i);
            if (window.mouseClicked(mouseX, mouseY, button)) {
                activeSettings = window;
                SETTINGS.remove(i);
                SETTINGS.add(window);
                return true;
            }
        }

        for (int i = WINDOWS.size() - 1; i >= 0; i--) {
            CategoryWindow window = WINDOWS.get(i);
            if (window.mouseClicked(mouseX, mouseY, button)) {
                active = window;
                WINDOWS.remove(i);
                WINDOWS.add(window);
                return true;
            }
        }
        return false;
    }

    @Override
    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        // Settings windows don't scroll, but they shouldn't let the wheel reach what's underneath either.
        if (settingsAt(mouseX, mouseY) != null) return true;

        for (int i = WINDOWS.size() - 1; i >= 0; i--) {
            CategoryWindow window = WINDOWS.get(i);
            if (window.isOver(mouseX, mouseY)) return window.mouseScrolled(mouseX, mouseY, amount);
        }
        return false;
    }

    @Override
    void mouseReleased(int button) {
        if (activeSettings != null) {
            activeSettings.mouseReleased();
            activeSettings = null;
        }
        if (active != null) {
            active.mouseReleased(button);
            active = null;
        }
    }

    @Override
    void mouseDragged(double mouseX, double mouseY) {
        if (activeSettings != null) activeSettings.mouseDragged(mouseX, mouseY);
        else if (active != null) active.mouseDragged(mouseX, mouseY);
    }
}
