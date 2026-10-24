package com.cyanite.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/** One tab of the click GUI. Everything is in the screen's own UI units. */
abstract class Page {
    static final int TOP = 34;

    protected int width;
    protected int height;
    /** Seconds since this page was shown; drives its entrance animation. */
    protected float shownFor;
    /** Where the page's main panel was last drawn, so the tutorial can point at it. */
    protected Rect bounds = Rect.EMPTY;

    Rect bounds() {
        return bounds;
    }

    void resize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    void onShow() {
    }

    abstract void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha);

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    void mouseReleased(int button) {
    }

    void mouseDragged(double mouseX, double mouseY) {
    }

    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return false;
    }

    boolean keyPressed(KeyEvent event) {
        return false;
    }

    boolean charTyped(CharacterEvent event) {
        return false;
    }

    /** True while a text field on this page has focus. */
    boolean isTyping() {
        return false;
    }

    /** Fade-in for single-panel pages, 0 to 1. */
    protected float entrance() {
        return Anim.enabled() ? Anim.easeOut(shownFor / 0.25f) : 1;
    }

    static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
