package com.cyanite.client.gui;

import com.cyanite.client.config.Profiles;
import com.cyanite.client.donut.DonutSmp;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The Donut SMP tab: the server's address and status on the left, command shortcuts on the right. */
final class DonutPage extends Page {
    private static final int SERVER_W = 200;
    private static final int COMMANDS_W = 230;
    private static final int GAP = 10;
    private static final int HEADER = 18;
    private static final int PAD = 6;
    private static final int FIELD = 14;
    private static final int ROW = 14;
    private static final int CHIP = 16;
    private static final int BUTTON_W = 40;
    private static final float STATUS_TIME = 3;

    private final TextField address = new TextField(64, cp -> cp > 32 && cp < 127);
    private final TextField newCommand = new TextField(48, cp -> cp >= 32 && cp < 127);
    private final List<Button> buttons = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();

    private String status = "";
    private float statusLeft;
    private float online;

    private record Button(String key, int x, int y, int w, int h, Runnable left, Runnable right) {
    }

    @Override
    void onShow() {
        address.setText(DonutSmp.address());
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        float a = alpha * entrance();
        buttons.clear();
        statusLeft = Math.max(0, statusLeft - dt);
        online = Anim.approach(online, DonutSmp.isConnected() ? 1 : 0, dt, 10);

        // Typing in the box updates the saved address as you go.
        if (!address.text().equals(DonutSmp.address())) DonutSmp.setAddress(address.text());

        int x = (width - SERVER_W - GAP - COMMANDS_W) / 2;
        int y = TOP + Math.round((1 - entrance()) * -6);
        renderServer(g, x, y, mouseX, mouseY, dt, a);
        renderCommands(g, x + SERVER_W + GAP, y, mouseX, mouseY, dt, a);
    }

    private void renderServer(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        int body = PAD + 10 + FIELD + 8 + ROW * 4 + PAD;
        boolean hovered = inside(mouseX, mouseY, x, y, SERVER_W, HEADER + body);
        Panel.draw(g, x, y, SERVER_W, HEADER, body, a, hovered || address.isFocused() ? 0.6f : 0.35f, 0, 1);
        bounds = new Rect(x, y, SERVER_W, HEADER + body);
        Text.draw(g, DonutSmp.NAME, x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));
        statusDot(g, x + SERVER_W - 12, y + HEADER / 2, a);

        int top = y + HEADER + PAD;
        Text.draw(g, "Server address", x + PAD + 2, top, Theme.fade(Theme.TEXT_FAINT, a));

        int fieldY = top + 10;
        int fieldW = SERVER_W - PAD * 2 - BUTTON_W - 4;
        address.render(g, x + PAD, fieldY, fieldW, FIELD, "play.example.com", mouseX, mouseY, dt, a);

        boolean canJoin = DonutSmp.canJoin();
        String joinLabel = DonutSmp.isConnected() ? "On" : "Join";
        button(g, "join", x + SERVER_W - PAD - BUTTON_W, fieldY, BUTTON_W, FIELD, joinLabel, canJoin, mouseX, mouseY, dt, a,
            DonutSmp::join, null);

        int rowY = fieldY + FIELD + 8;
        boolean connected = DonutSmp.isConnected();
        row(g, x, rowY, "Status", connected ? "Connected" : "Not connected",
            connected ? 0xFF5BE07A : Theme.TEXT_DIM, a);
        row(g, x, rowY + ROW, "Ping", connected ? ping() : "-", Theme.TEXT_DIM, a);
        row(g, x, rowY + ROW * 2, "Session", connected ? duration(DonutSmp.sessionMillis()) : "-", Theme.TEXT_DIM, a);
        profileRow(g, x, rowY + ROW * 3, mouseX, mouseY, dt, a);
    }

    private void statusDot(GuiGraphics g, int cx, int cy, float a) {
        int s = Render2D.scale();
        int d = 4 * s;
        int color = Theme.mix(Theme.TEXT_FAINT, 0xFF5BE07A, online);
        Render2D.pixels(g);
        Render2D.glow(g, cx * s - d / 2, cy * s - d / 2, d, d, d / 2, 5 * s, Theme.alpha(0x5BE07A, online * 140 * Theme.glow() * a));
        Render2D.rect(g, cx * s - d / 2, cy * s - d / 2, d, d, d / 2, Theme.fade(color, a));
        Render2D.end(g);
    }

    private void row(GuiGraphics g, int x, int y, String label, String value, int valueColor, float a) {
        int textY = y + (ROW - Text.CAP) / 2;
        Text.draw(g, label, x + PAD + 2, textY, Theme.fade(Theme.TEXT_DIM, a));
        Text.draw(g, value, x + SERVER_W - PAD - 2 - Text.width(value), textY, Theme.fade(valueColor, a));
    }

    private void profileRow(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        String value = DonutSmp.joinProfile();
        boolean over = inside(mouseX, mouseY, x + 2, y, SERVER_W - 4, ROW);
        float h = hover("profile", over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        int s = Render2D.scale();
        Render2D.pixels(g);
        Render2D.rect(g, (x + 2) * s, y * s, (SERVER_W - 4) * s, ROW * s, 2 * s, Theme.alpha(0xFFFFFF, h * 10 * a));
        Render2D.chevron(g, (x + SERVER_W - PAD - 3) * s, y * s + ROW * s / 2, (float) (-Math.PI / 2),
            Theme.fade(Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, h), a));
        Render2D.end(g);

        int textY = y + (ROW - Text.CAP) / 2;
        Text.draw(g, "Profile on join", x + PAD + 2, textY, Theme.fade(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h), a));
        int color = value.equals(DonutSmp.NO_PROFILE) ? Theme.TEXT_FAINT : Theme.accentText();
        Text.draw(g, value, x + SERVER_W - PAD - 9 - Text.width(value), textY, Theme.fade(color, a));

        buttons.add(new Button("profile", x + 2, y, SERVER_W - 4, ROW, () -> cycleProfile(1), () -> cycleProfile(-1)));
    }

    private void cycleProfile(int direction) {
        List<String> options = new ArrayList<>();
        options.add(DonutSmp.NO_PROFILE);
        Profiles.list().forEach(p -> options.add(p.name()));
        int index = Math.max(0, options.indexOf(DonutSmp.joinProfile()));
        DonutSmp.setJoinProfile(options.get(Math.floorMod(index + direction, options.size())));
    }

    private void renderCommands(GuiGraphics g, int x, int y, int mouseX, int mouseY, float dt, float a) {
        List<String> commands = DonutSmp.commands();
        int innerW = COMMANDS_W - PAD * 2;

        // Lay the chips out in rows, wrapping at the panel edge.
        List<int[]> chips = new ArrayList<>();
        int cx = 0;
        int cy = 0;
        for (String command : commands) {
            int w = Text.width(command) + 12;
            if (cx > 0 && cx + w > innerW) {
                cx = 0;
                cy += CHIP + 4;
            }
            chips.add(new int[]{cx, cy, w});
            cx += w + 4;
        }
        int chipsHeight = commands.isEmpty() ? ROW : cy + CHIP;
        int body = PAD + chipsHeight + 8 + FIELD + 6 + 12;
        boolean hovered = inside(mouseX, mouseY, x, y, COMMANDS_W, HEADER + body);

        Panel.draw(g, x, y, COMMANDS_W, HEADER, body, a, hovered || newCommand.isFocused() ? 0.6f : 0.35f, 0, 1);
        bounds = bounds.union(new Rect(x, y, COMMANDS_W, HEADER + body));
        Text.draw(g, "Commands", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));
        String count = Integer.toString(commands.size());
        Text.draw(g, count, x + COMMANDS_W - 8 - Text.width(count), y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));

        int top = y + HEADER + PAD;
        if (commands.isEmpty()) {
            String empty = "No commands yet";
            Text.draw(g, empty, x + (COMMANDS_W - Text.width(empty)) / 2, top + (ROW - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, a));
        }
        for (int i = 0; i < commands.size(); i++) {
            int[] chip = chips.get(i);
            chip(g, commands.get(i), x + PAD + chip[0], top + chip[1], chip[2], mouseX, mouseY, dt, a);
        }

        int fieldY = top + chipsHeight + 8;
        newCommand.render(g, x + PAD, fieldY, innerW - BUTTON_W - 4, FIELD, "/command", mouseX, mouseY, dt, a);
        button(g, "add", x + COMMANDS_W - PAD - BUTTON_W, fieldY, BUTTON_W, FIELD, "Add", !newCommand.text().isBlank(),
            mouseX, mouseY, dt, a, this::addCommand, null);

        int footerY = fieldY + FIELD + 6;
        if (statusLeft > 0) {
            Text.draw(g, status, x + PAD + 2, footerY, Theme.fade(Theme.TEXT_DIM, a * Math.min(1, statusLeft / 0.4f)));
        } else {
            Text.draw(g, "Click to run, right click to remove", x + PAD + 2, footerY, Theme.fade(Theme.TEXT_FAINT, a));
        }
    }

    private void chip(GuiGraphics g, String command, int x, int y, int w, int mouseX, int mouseY, float dt, float a) {
        boolean over = inside(mouseX, mouseY, x, y, w, CHIP);
        float h = hover("chip:" + command, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, CHIP * s, r, 5 * s, Theme.alpha(accent, h * 60 * Theme.glow() * a));
        Render2D.rect(g, x * s, y * s, w * s, CHIP * s, r, Theme.fade(Theme.mix(Theme.BORDER, accent, h * 0.8f), a));
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, CHIP * s - 2, Math.max(0, r - 1), Theme.fade(Theme.FIELD, a));
        Render2D.end(g);

        Text.draw(g, command, x + 6, y + (CHIP - Text.CAP) / 2, Theme.fade(Theme.mix(Theme.accentText(), Theme.TEXT, h), a));
        buttons.add(new Button("chip:" + command, x, y, w, CHIP, () -> run(command), () -> {
            DonutSmp.removeCommand(command);
            setStatus("Removed " + command);
        }));
    }

    private void button(GuiGraphics g, String key, int x, int y, int w, int h, String label, boolean enabled,
                        int mouseX, int mouseY, float dt, float a, Runnable action, Runnable rightAction) {
        boolean over = enabled && inside(mouseX, mouseY, x, y, w, h);
        float hv = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Widgets.button(g, x, y, w, h, label, hv, enabled, a);
        if (enabled) buttons.add(new Button(key, x, y, w, h, action, rightAction));
    }

    private void run(String command) {
        setStatus(DonutSmp.run(command) ? "Sent " + command : "Not connected to a server");
    }

    private void addCommand() {
        DonutSmp.addCommand(newCommand.text());
        newCommand.clear();
        setStatus("Added");
    }

    private String ping() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) return "-";
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? "-" : info.getLatency() + " ms";
    }

    private static String duration(long millis) {
        long seconds = millis / 1000;
        long h = seconds / 3600;
        long m = seconds / 60 % 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%dh %02dm", h, m) : String.format("%dm %02ds", m, s);
    }

    private float hover(String key, boolean over, float dt) {
        float value = Anim.approach(hover.getOrDefault(key, 0f), over ? 1 : 0, dt, 16);
        hover.put(key, value);
        return value;
    }

    private void setStatus(String text) {
        status = text;
        statusLeft = STATUS_TIME;
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean focusedAddress = address.mouseClicked(mouseX, mouseY);
        boolean focusedCommand = newCommand.mouseClicked(mouseX, mouseY);

        for (Button b : List.copyOf(buttons)) {
            if (!inside(mouseX, mouseY, b.x, b.y, b.w, b.h)) continue;
            Runnable action = button == 1 ? b.right : button == 0 ? b.left : null;
            if (action != null) action.run();
            return true;
        }
        return focusedAddress || focusedCommand;
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        boolean enter = event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
        if (enter && newCommand.isFocused() && !newCommand.text().isBlank()) {
            addCommand();
            return true;
        }
        if (enter && address.isFocused()) {
            address.setFocused(false);
            return true;
        }
        return address.keyPressed(event) || newCommand.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return address.charTyped(event) || newCommand.charTyped(event);
    }

    @Override
    boolean isTyping() {
        return address.isFocused() || newCommand.isFocused();
    }
}
