package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

final class ModulesPage extends Page {
    private static final int GAP = 8;

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
