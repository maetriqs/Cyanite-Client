package com.cyanite.client.gui;

import com.cyanite.client.gui.render.Text;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The pause menu in Cyanite's look: its buttons, and any other mod's, as a column over the blurred world under a
 * small crystal and the menu's title, with Cyanite Menu after Back to Game. Rows keep the screen's own pairing,
 * so a mod that swaps a button for its own keeps its place.
 */
public final class HomePause extends HomeColumn {
    private static final int MARK = 26, GAP_TITLE = 10, TITLE = 11, GAP_ROWS = 22;
    private static final int HEADER = MARK + GAP_TITLE + TITLE + GAP_ROWS;

    /** Where the screen put its buttons: they follow their stand-ins while hidden, and go back after. */
    private final Map<AbstractWidget, int[]> placed = new HashMap<>();

    public HomePause(Screen screen, Consumer<AbstractButton> add, Consumer<GuiEventListener> remove) {
        super(screen, add, remove);
    }

    @Override
    void style() {
        List<Button> buttons = new ArrayList<>();
        String title = screen.getTitle().getString();
        for (GuiEventListener child : List.copyOf(screen.children())) {
            // The title is drawn with the crystal.
            if (child instanceof StringWidget text && text.visible && text.getMessage().getString().equals(title)) hide(text);
            // Icon-only buttons have nothing to say in a column: they stay as they are.
            else if (child instanceof Button b && b.visible && !(b instanceof PlainTextButton) && !b.getMessage().getString().isBlank()) buttons.add(b);
        }
        buttons.sort(Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));

        // The screen's rows as they were: one button across, or two side by side (more are paired up).
        boolean first = true;
        for (int i = 0; i < buttons.size(); ) {
            int y = buttons.get(i).getY();
            int end = i;
            while (end < buttons.size() && buttons.get(end).getY() == y) end++;
            for (int j = i; j < end; j += 2) {
                line(first ? Home.BIG : Home.ROW, buttons.get(j), j + 1 < end ? buttons.get(j + 1) : null);
                if (first) {
                    ours(Kind.MENU, Component.literal("Cyanite Menu"), Home.ROW);
                    first = false;
                }
            }
            i = end;
        }
    }

    @Override
    Home.Metrics metrics() {
        return Home.metrics(HEADER, rows);
    }

    @Override
    int columnTop() {
        return m.top() + m.u(HEADER);
    }

    /** The hidden buttons sit under their stand-ins: the game marks Disconnect when a report is waiting, and the mark lands right. */
    @Override
    void placeMore() {
        for (StandIn s : standIns) {
            Button b = s.original;
            if (b == null) continue;
            placed.computeIfAbsent(b, k -> new int[] {k.getX(), k.getY(), k.getWidth(), k.getHeight()});
            b.setRectangle(s.getWidth(), s.getHeight(), s.getX(), s.getY());
        }
    }

    @Override
    void dropped() {
        placed.forEach((b, at) -> b.setRectangle(at[2], at[3], at[0], at[1]));
        placed.clear();
    }

    /** Under the buttons, over the blurred world: a tint, and the crystal over the menu's title. */
    public void backdrop(GuiGraphics g) {
        if (m == null) return;
        Home.begin(g, m);
        Backdrop.overWorld(g, m);
        float in = entrance(0);
        int h = m.u(MARK);
        int y = m.top() - Math.round((1 - in) * m.u(6));
        Home.crystal(g, m.cx() - Home.crystalWidth(h) / 2, y, h, in, true);
        int cap = m.u(TITLE);
        String title = screen.getTitle().getString();
        Text.Face face = Text.face(true, cap, title);
        float tracking = 0.04f * cap;
        face.draw(g, title, Math.round(m.cx() - face.width(title, tracking) / 2f), y + m.u(MARK + GAP_TITLE), Theme.fade(Theme.TEXT, in), tracking);
        Home.end(g);
    }
}
