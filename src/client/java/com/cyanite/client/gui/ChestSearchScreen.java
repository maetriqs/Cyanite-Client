package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.hud.Hud;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.modules.world.ChestIndex;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * Chest Index's search: one slim bar near the top of the screen, the world still showing round
 * it. Type an item and the matches drop down under it, a line each, the picked one opening up to
 * show where it is. Enter (or a click) lights those chests up and gets out of the way.
 */
public class ChestSearchScreen extends Screen {
    private static final int W = 300;
    private static final int PAD = 6;
    private static final int FIELD = 18;
    private static final int ITEM_ROW = 16;
    private static final int SPOT_ROW = 12;
    private static final int MAX_HITS = 6;
    private static final int MAX_SPOTS = 4;

    private final ChestIndex index;
    private final int openKey;
    private final TextField field = new TextField(40, cp -> cp >= 32 && cp != 127);
    private boolean skippedOpenKey;
    private String query = "";
    private List<ChestIndex.Hit> hits = List.of();
    private int selected;
    private int x;
    private int y;
    private int rowsTop;

    public ChestSearchScreen(ChestIndex index, int openKey) {
        super(Component.literal("Chest search"));
        this.index = index;
        this.openKey = openKey;
        field.setFocused(true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Nothing: the world stays in view round the bar.
    }

    private int unit() {
        return Hud.unit();
    }

    private double toUi(double gui) {
        return gui * minecraft.getWindow().getGuiScale() / unit();
    }

    @Override
    public void render(GuiGraphics g, int guiMouseX, int guiMouseY, float partialTick) {
        if (!field.text().equals(query)) {
            query = field.text();
            hits = index.search(query);
            selected = 0;
        }
        Window window = minecraft.getWindow();
        int uiW = window.getWidth() / unit();
        int uiH = window.getHeight() / unit();
        int mouseX = (int) toUi(guiMouseX);
        int mouseY = (int) toUi(guiMouseY);
        x = (uiW - W) / 2;
        y = uiH / 6;

        int shownHits = Math.min(MAX_HITS, hits.size());
        int spotRows = 0;
        if (shownHits > 0) {
            int spots = hits.get(selected).spots().size();
            spotRows = Math.min(MAX_SPOTS, spots) + (spots > MAX_SPOTS ? 1 : 0);
        }
        int body = query.isBlank() || hits.isEmpty() ? SPOT_ROW + 4 : shownHits * ITEM_ROW + spotRows * SPOT_ROW + 4;
        int h = PAD + FIELD + 4 + body + SPOT_ROW + PAD;

        Hud.begin(g);
        int s = Render2D.scale();
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, W * s, h * s, 4 * s, 8 * s, Theme.alpha(accent, 50 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, W * s, h * s, 4 * s, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, W * s - 2, h * s - 2, 4 * s - 1, 0xF0101217);
        Render2D.end(g);

        field.render(g, x + PAD, y + PAD, W - PAD * 2, FIELD, "Find an item: diamonds, elytra, shulker...", mouseX, mouseY, 0.016f, 1);
        rowsTop = y + PAD + FIELD + 4;
        int ry = rowsTop;
        if (query.isBlank()) {
            Text.draw(g, index.indexed() + " containers remembered here. Open chests to add them", x + PAD + 2, ry + 2, Theme.TEXT_FAINT);
        } else if (hits.isEmpty()) {
            Text.draw(g, "Not in any chest you've opened here", x + PAD + 2, ry + 2, Theme.TEXT_FAINT);
        } else {
            for (int i = 0; i < shownHits; i++) {
                ChestIndex.Hit hit = hits.get(i);
                boolean over = Page.inside(mouseX, mouseY, x, ry, W, ITEM_ROW);
                if (i == selected || over) {
                    Render2D.pixels(g);
                    Render2D.rect(g, (x + 3) * s, ry * s, (W - 6) * s, ITEM_ROW * s, 2 * s, Theme.alpha(accent, i == selected ? 40 : 18));
                    Render2D.end(g);
                }
                g.pose().pushMatrix();
                g.pose().translate(x + PAD + 1, ry + 2);
                g.pose().scale(0.75f);
                g.renderItem(hit.icon(), 0, 0);
                g.pose().popMatrix();
                int tx = x + PAD + 17;
                int ty = ry + (ITEM_ROW - Text.CAP) / 2;
                String count = "x" + String.format(Locale.ROOT, "%,d", hit.total());
                String where = places(hit);
                int room = W - PAD * 2 - 17 - Text.width(count) - 8 - Text.width(where) - 6;
                Text.draw(g, fit(hit.name(), room), tx, ty, Theme.TEXT);
                Text.draw(g, count, tx + Math.min(Text.width(hit.name()), room) + 5, ty, Theme.accentText());
                Text.draw(g, where, x + W - PAD - Text.width(where), ty, Theme.TEXT_FAINT);
                ry += ITEM_ROW;
                if (i == selected) {
                    List<ChestIndex.Spot> spots = hit.spots();
                    for (int k = 0; k < Math.min(MAX_SPOTS, spots.size()); k++) {
                        ChestIndex.Spot spot = spots.get(k);
                        String at = spot.pos() == null ? "Your ender chest"
                            : StreamerMode.mask(spot.pos().getX() + ", " + spot.pos().getY() + ", " + spot.pos().getZ());
                        String detail = (spot.pos() == null ? "" : Math.round(spot.distance()) + "m  ") + "x" + spot.count()
                            + (spot.inShulker() ? " in a shulker" : "");
                        Text.draw(g, at, tx + 4, ry + (SPOT_ROW - Text.CAP) / 2, Theme.TEXT_DIM);
                        Text.draw(g, detail, x + W - PAD - Text.width(detail), ry + (SPOT_ROW - Text.CAP) / 2, Theme.TEXT_FAINT);
                        ry += SPOT_ROW;
                    }
                    if (spots.size() > MAX_SPOTS) {
                        Text.draw(g, "+" + (spots.size() - MAX_SPOTS) + " more", tx + 4, ry + (SPOT_ROW - Text.CAP) / 2, Theme.TEXT_FAINT);
                        ry += SPOT_ROW;
                    }
                }
            }
        }
        ChestIndex.Hit lit = index.shown();
        String foot = lit != null ? "Lit up: " + lit.name() + "   Delete clears it   Esc closes" : "Enter lights them up   Up/Down picks   Esc closes";
        Text.draw(g, fit(foot, W - PAD * 2), x + PAD + 2, y + h - PAD - Text.CAP - 1, Theme.TEXT_FAINT);
        Hud.end(g);
    }

    /** "3 chests, 12m" style: how many places, and how far the nearest. */
    private static String places(ChestIndex.Hit hit) {
        long inWorld = hit.spots().stream().filter(sp -> sp.pos() != null).count();
        boolean ender = hit.spots().stream().anyMatch(sp -> sp.pos() == null);
        StringBuilder sb = new StringBuilder();
        if (inWorld > 0) {
            sb.append(inWorld).append(inWorld == 1 ? " chest, " : " chests, ");
            double near = hit.spots().stream().filter(sp -> sp.pos() != null).mapToDouble(ChestIndex.Spot::distance).min().orElse(0);
            sb.append(Math.round(near)).append("m");
        }
        if (ender) sb.append(sb.isEmpty() ? "" : " + ").append("ender chest");
        return sb.toString();
    }

    private static String fit(String text, int room) {
        if (Text.width(text) <= room) return text;
        String cut = text;
        while (!cut.isEmpty() && Text.width(cut + "…") > room) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (!hits.isEmpty()) {
                index.show(hits.get(selected));
                onClose();
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN && !hits.isEmpty()) {
            selected = Math.min(Math.min(MAX_HITS, hits.size()) - 1, selected + 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP && !hits.isEmpty()) {
            selected = Math.max(0, selected - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE) {
            index.clearShown();
            return true;
        }
        return field.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        // The key that opened this would otherwise be typed straight into the box.
        if (!skippedOpenKey) {
            skippedOpenKey = true;
            String typed = event.codepointAsString().toUpperCase(Locale.ROOT);
            if (typed.length() == 1 && typed.charAt(0) == openKey) return true;
        }
        return field.charTyped(event) || super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = toUi(event.x());
        double my = toUi(event.y());
        if (!hits.isEmpty() && !query.isBlank()) {
            int ry = rowsTop;
            for (int i = 0; i < Math.min(MAX_HITS, hits.size()); i++) {
                if (Page.inside(mx, my, x, ry, W, ITEM_ROW)) {
                    index.show(hits.get(i));
                    onClose();
                    return true;
                }
                ry += ITEM_ROW;
                if (i == selected) {
                    int spots = hits.get(i).spots().size();
                    ry += (Math.min(MAX_SPOTS, spots) + (spots > MAX_SPOTS ? 1 : 0)) * SPOT_ROW;
                }
            }
        }
        field.mouseClicked(mx, my);
        field.setFocused(true);
        return true;
    }
}
