package com.cyanite.client.hud;

import com.cyanite.client.system.setting.Setting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Something drawn on the HUD. Its position is an anchor (which edge or centre of the
 * screen it hangs off) plus an offset from there, so it stays put when the window resizes.
 * All sizes are HUD units.
 */
public abstract class HudElement {
    public enum Horizontal { LEFT, CENTER, RIGHT }

    public enum Vertical { TOP, CENTER, BOTTOM }

    public final String name;

    private final List<Setting<?>> settings = new ArrayList<>();
    private final boolean defaultEnabled;
    private final Horizontal defaultH;
    private final Vertical defaultV;
    private final int defaultX;
    private final int defaultY;

    private boolean enabled;
    protected Horizontal horizontal;
    protected Vertical vertical;
    private int offsetX;
    private int offsetY;

    protected int width;
    protected int height;
    public int x;
    public int y;

    protected HudElement(String name, boolean enabled, Horizontal h, Vertical v, int offsetX, int offsetY) {
        this.name = name;
        this.defaultEnabled = enabled;
        this.defaultH = h;
        this.defaultV = v;
        this.defaultX = offsetX;
        this.defaultY = offsetY;
        this.enabled = enabled;
        resetPosition();
    }

    protected <S extends Setting<?>> S add(S setting) {
        settings.add(setting);
        return setting;
    }

    public List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void toggle() {
        enabled = !enabled;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Works out {@link #width} and {@link #height} for this frame. */
    public abstract void update(float dt, boolean editing);

    /** Draws at {@link #x}, {@link #y}. */
    public abstract void draw(GuiGraphics g, float alpha, boolean editing);

    public void place(int screenWidth, int screenHeight) {
        x = switch (horizontal) {
            case LEFT -> offsetX;
            case CENTER -> (screenWidth - width) / 2 + offsetX;
            case RIGHT -> screenWidth - offsetX - width;
        };
        y = switch (vertical) {
            case TOP -> offsetY;
            case CENTER -> (screenHeight - height) / 2 + offsetY;
            case BOTTOM -> screenHeight - offsetY - height;
        };
        x = Math.max(0, Math.min(x, screenWidth - width));
        y = Math.max(0, Math.min(y, screenHeight - height));
    }

    /** Moves to an absolute spot and re-picks the anchor from whichever third of the screen it's in. */
    public void moveTo(int newX, int newY, int screenWidth, int screenHeight) {
        newX = Math.max(0, Math.min(newX, screenWidth - width));
        newY = Math.max(0, Math.min(newY, screenHeight - height));

        int centerX = newX + width / 2;
        int centerY = newY + height / 2;
        horizontal = centerX < screenWidth / 3 ? Horizontal.LEFT : centerX > screenWidth * 2 / 3 ? Horizontal.RIGHT : Horizontal.CENTER;
        vertical = centerY < screenHeight / 3 ? Vertical.TOP : centerY > screenHeight * 2 / 3 ? Vertical.BOTTOM : Vertical.CENTER;

        offsetX = switch (horizontal) {
            case LEFT -> newX;
            case CENTER -> newX - (screenWidth - width) / 2;
            case RIGHT -> screenWidth - newX - width;
        };
        offsetY = switch (vertical) {
            case TOP -> newY;
            case CENTER -> newY - (screenHeight - height) / 2;
            case BOTTOM -> screenHeight - newY - height;
        };
        x = newX;
        y = newY;
    }

    public void resetPosition() {
        horizontal = defaultH;
        vertical = defaultV;
        offsetX = defaultX;
        offsetY = defaultY;
    }

    public void reset() {
        enabled = defaultEnabled;
        resetPosition();
        for (Setting<?> setting : settings) setting.reset();
    }

    public JsonObject save() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("anchor", horizontal + " " + vertical);
        json.addProperty("x", offsetX);
        json.addProperty("y", offsetY);

        JsonObject values = new JsonObject();
        for (Setting<?> setting : settings) values.add(setting.name, setting.toJson());
        json.add("settings", values);
        return json;
    }

    public void load(JsonObject json) {
        try {
            if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
            if (json.has("anchor")) {
                String[] anchor = json.get("anchor").getAsString().split(" ");
                horizontal = Horizontal.valueOf(anchor[0]);
                vertical = Vertical.valueOf(anchor[1]);
            }
            if (json.has("x")) offsetX = json.get("x").getAsInt();
            if (json.has("y")) offsetY = json.get("y").getAsInt();
        } catch (RuntimeException e) {
            resetPosition();
        }

        if (json.get("settings") instanceof JsonObject values) {
            for (Setting<?> setting : settings) {
                JsonElement value = values.get(setting.name);
                if (value != null) setting.fromJson(value);
            }
        }
    }
}
