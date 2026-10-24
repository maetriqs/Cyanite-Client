package com.cyanite.client.gui;

import com.cyanite.client.account.Accounts;
import com.cyanite.client.account.MicrosoftAuth;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Microsoft accounts (added with a sign-in code) and cracked accounts (just a name), switched
 * between in one click. Switching changes who you are the next time you join a server.
 */
final class AccountsPage extends Page {
    private static final int W = 260;
    private static final int HEADER = 18;
    private static final int PAD = 6;
    private static final int ROW = 16;
    private static final float CONFIRM_TIME = 3;
    private static final float STATUS_TIME = 5;

    private enum Adding { NONE, REQUESTING, WAITING, SIGNING_IN }

    private static final int FIELD = 14;
    private static final int ADD_W = 36;

    private final List<Button> buttons = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();
    private final TextField crackedName = new TextField(16, cp -> cp < 128 && (Character.isLetterOrDigit(cp) || cp == '_'));
    private boolean crackedOpen;

    private int x;
    private int y;
    private UUID confirming;
    private float confirmLeft;
    private String status = "";
    private boolean statusError;
    private float statusLeft;
    private boolean busy;

    // The sign-in in progress. Written from the polling thread, so volatile.
    private volatile Adding adding = Adding.NONE;
    private volatile MicrosoftAuth.DeviceCode code;
    private volatile int attempt;

    private record Button(String key, int x, int y, int w, int h, Runnable action) {
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY, float dt, float alpha) {
        float a = alpha * entrance();
        x = (width - W) / 2;
        y = TOP + Math.round((1 - entrance()) * -6);
        buttons.clear();
        confirmLeft = Math.max(0, confirmLeft - dt);
        if (confirmLeft == 0) confirming = null;
        statusLeft = Math.max(0, statusLeft - dt);

        List<Accounts.Account> accounts = Accounts.list();
        int rows = 1 + accounts.size();
        int addBox = (adding == Adding.NONE ? 0 : 58 + PAD) + (crackedOpen ? FIELD + PAD : 0);
        int body = PAD + 12 + PAD + addBox + rows * ROW + PAD + 12;
        boolean hovered = inside(mouseX, mouseY, x, y, W, HEADER + body);

        Panel.draw(g, x, y, W, HEADER, body, a, hovered || crackedName.isFocused() ? 0.6f : 0.35f, 0, 1);
        bounds = new Rect(x, y, W, HEADER + body);
        Text.draw(g, "Accounts", x + 8, y + (HEADER - Text.CAP) / 2, Theme.fade(Theme.TEXT, a));
        if (adding == Adding.NONE && !crackedOpen) {
            int hy = y + (HEADER - Text.CAP) / 2;
            String microsoft = "+ Microsoft";
            String cracked = "+ Cracked";
            int mx = x + W - 8 - Text.width(microsoft);
            textButton(g, "add", microsoft, mx, hy, mouseX, mouseY, dt, a, Theme.accentText(), 0xFFFFFFFF, this::startAdding);
            textButton(g, "cracked", cracked, mx - 12 - Text.width(cracked), hy, mouseX, mouseY, dt, a,
                Theme.TEXT_DIM, Theme.accentText(), this::openCracked);
        }

        int lineY = y + HEADER + PAD;
        String playing = "Playing as ";
        String name = Minecraft.getInstance().getUser().getName();
        Text.draw(g, playing, x + PAD + 2, lineY, Theme.fade(Theme.TEXT_DIM, a));
        Text.draw(g, name, x + PAD + 2 + Text.width(playing), lineY, Theme.fade(Theme.accentText(), a));

        int listY = lineY + 12 + PAD;
        if (crackedOpen) {
            crackedName.render(g, x + PAD, listY, W - PAD * 2 - ADD_W - 4, FIELD, "Cracked username", mouseX, mouseY, dt, a);
            addButton(g, x + W - PAD - ADD_W, listY, mouseX, mouseY, dt, a);
            listY += FIELD + PAD;
        }
        if (adding != Adding.NONE) {
            renderAdding(g, listY, mouseX, mouseY, dt, a);
            listY += 58 + PAD;
        }

        UUID current = Accounts.current();
        var launcher = Accounts.launcher();
        if (launcher != null) {
            renderRow(g, "launcher", launcher.getName(), "Launcher", launcher.getProfileId().equals(current), false, listY,
                mouseX, mouseY, dt, a, this::useLauncher, null);
        }
        for (int i = 0; i < accounts.size(); i++) {
            Accounts.Account account = accounts.get(i);
            boolean isLauncher = launcher != null && account.uuid().equals(launcher.getProfileId());
            String tag = account.cracked() ? "Cracked" : isLauncher ? "Saved" : null;
            renderRow(g, account.uuid().toString(), account.name(), tag, account.uuid().equals(current), true,
                listY + (i + 1) * ROW, mouseX, mouseY, dt, a, () -> use(account), () -> remove(account));
        }

        int statusY = listY + rows * ROW + PAD;
        if (statusLeft > 0 || busy) {
            float fade = busy ? 1 : Math.min(1, statusLeft / 0.4f);
            Text.draw(g, status, x + PAD + 2, statusY, Theme.fade(statusError ? Theme.DANGER : Theme.TEXT_DIM, a * fade));
        } else if (Minecraft.getInstance().level != null) {
            Text.draw(g, "Switching applies the next time you join a server", x + PAD + 2, statusY, Theme.fade(Theme.TEXT_FAINT, a));
        }
    }

    private void renderAdding(GuiGraphics g, int boxY, int mouseX, int mouseY, float dt, float a) {
        int s = Render2D.scale();
        int bx = x + PAD;
        int bw = W - PAD * 2;
        int bh = 58;
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        Render2D.pixels(g);
        Render2D.rect(g, bx * s, boxY * s, bw * s, bh * s, r, Theme.fade(Theme.FIELD, a));
        Render2D.end(g);

        MicrosoftAuth.DeviceCode c = code;
        if (adding == Adding.WAITING && c != null) {
            Text.draw(g, "Go to microsoft.com/link and enter this code:", bx + 6, boxY + 6, Theme.fade(Theme.TEXT_DIM, a));
            // The code, twice the size, so it's easy to read across.
            g.pose().pushMatrix();
            g.pose().translate(bx + 6, boxY + 18);
            g.pose().scale(2);
            Text.draw(g, c.userCode(), 0, 0, Theme.fade(Theme.accentText(), a));
            g.pose().popMatrix();

            int by = boxY + bh - Text.CAP - 7;
            long left = Math.max(0, (c.expiresAt() - System.currentTimeMillis()) / 1000);
            String timer = left / 60 + ":" + String.format("%02d", left % 60);
            Text.draw(g, timer, bx + bw - 6 - Text.width(timer), by, Theme.fade(Theme.TEXT_FAINT, a));

            int bxx = bx + 6;
            bxx = textButton(g, "open", "Open link", bxx, by, mouseX, mouseY, dt, a, Theme.accentText(), 0xFFFFFFFF,
                () -> Util.getPlatform().openUri(c.link())) + 12;
            bxx = textButton(g, "copy", "Copy code", bxx, by, mouseX, mouseY, dt, a, Theme.TEXT_DIM, Theme.accentText(),
                () -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(c.userCode());
                    setStatus("Code copied", false);
                }) + 12;
            textButton(g, "cancel", "Cancel", bxx, by, mouseX, mouseY, dt, a, Theme.TEXT_FAINT, Theme.DANGER, this::cancelAdding);
        } else {
            String text = adding == Adding.SIGNING_IN ? "Signing in…" : "Getting a sign-in code from Microsoft…";
            Text.draw(g, text, bx + (bw - Text.width(text)) / 2, boxY + (bh - Text.CAP) / 2 - 4, Theme.fade(Theme.TEXT_DIM, a));
            if (adding == Adding.REQUESTING) {
                String cancel = "Cancel";
                textButton(g, "cancel", cancel, bx + (bw - Text.width(cancel)) / 2, boxY + bh - Text.CAP - 7, mouseX, mouseY, dt, a,
                    Theme.TEXT_FAINT, Theme.DANGER, this::cancelAdding);
            }
        }
    }

    private void renderRow(GuiGraphics g, String key, String name, String tag, boolean active, boolean removable, int rowY,
                           int mouseX, int mouseY, float dt, float a, Runnable use, Runnable remove) {
        int s = Render2D.scale();
        boolean over = inside(mouseX, mouseY, x + 3, rowY, W - 6, ROW);
        float h = hover("row:" + key, over, dt);

        Render2D.pixels(g);
        Render2D.rect(g, (x + 3) * s, rowY * s, (W - 6) * s, ROW * s, 2 * s, Theme.alpha(0xFFFFFF, h * 10 * a));
        if (active) {
            int accent = Theme.accent();
            int barW = Math.max(2, Math.round(1.5f * s));
            int barY = (rowY + 4) * s;
            int barH = (ROW - 8) * s;
            Render2D.fadeRight(g, (x + 3) * s, rowY * s, (W - 6) * s, ROW * s, Theme.alpha(accent, 50 * a));
            Render2D.glow(g, (x + 3) * s, barY, barW, barH, barW / 2, 5 * s, Theme.alpha(accent, 160 * Theme.glow() * a));
            Render2D.rect(g, (x + 3) * s, barY, barW, barH, barW / 2, Theme.fade(accent, a));
        }
        Render2D.end(g);

        int textY = rowY + (ROW - Text.CAP) / 2;
        Text.draw(g, name, x + 10, textY, Theme.fade(active ? Theme.accentText() : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, h), a));
        if (tag != null) Text.draw(g, tag, x + 10 + Text.width(name) + 6, textY, Theme.fade(Theme.TEXT_FAINT, a));

        int right = x + W - 10;
        if (removable) {
            boolean confirm = key.equals(String.valueOf(confirming));
            String del = confirm ? "Sure?" : "Remove";
            right -= Text.width(del);
            textButton(g, "del:" + key, del, right, textY, mouseX, mouseY, dt, a, confirm ? Theme.DANGER : Theme.TEXT_FAINT, Theme.DANGER, remove);
            right -= 10;
        }
        if (active) {
            String label = "Active";
            Text.draw(g, label, right - Text.width(label), textY, Theme.fade(Theme.accentText(), a));
        } else if (!busy) {
            String label = "Use";
            right -= Text.width(label);
            textButton(g, "use:" + key, label, right, textY, mouseX, mouseY, dt, a, Theme.TEXT_DIM, Theme.accentText(), use);
        }
    }

    private void addButton(GuiGraphics g, int bx, int by, int mouseX, int mouseY, float dt, float a) {
        boolean over = inside(mouseX, mouseY, bx, by, ADD_W, FIELD);
        float h = hover("addcracked", over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);

        int s = Render2D.scale();
        int r = Math.round(Math.min(Theme.radius(), 3) * s);
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.glow(g, bx * s, by * s, ADD_W * s, FIELD * s, r, 6 * s, Theme.alpha(accent, (30 + h * 60) * Theme.glow() * a));
        Render2D.rect(g, bx * s, by * s, ADD_W * s, FIELD * s, r, Theme.alpha(accent, (70 + h * 60) * a));
        Render2D.end(g);

        String label = "Add";
        Text.draw(g, label, bx + (ADD_W - Text.width(label)) / 2, by + (FIELD - Text.CAP) / 2,
            Theme.fade(Theme.mix(Theme.accentText(), 0xFFFFFFFF, h), a));
        buttons.add(new Button("addcracked", bx, by, ADD_W, FIELD, this::addCracked));
    }

    /** @return the x just past the button, for laying out a row of them. */
    private int textButton(GuiGraphics g, String key, String label, int bx, int by, int mouseX, int mouseY,
                           float dt, float a, int color, int hoverColor, Runnable action) {
        int w = Text.width(label);
        boolean over = inside(mouseX, mouseY, bx - 3, by - 3, w + 6, Text.CAP + 6);
        float h = hover(key, over, dt);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        Text.draw(g, label, bx, by, Theme.fade(Theme.mix(color, hoverColor, h), a));
        buttons.add(new Button(key, bx - 3, by - 3, w + 6, Text.CAP + 6, action));
        return bx + w;
    }

    private float hover(String key, boolean over, float dt) {
        float value = Anim.approach(hover.getOrDefault(key, 0f), over ? 1 : 0, dt, 16);
        hover.put(key, value);
        return value;
    }

    // ---- Actions

    private void openCracked() {
        crackedOpen = true;
        crackedName.clear();
        crackedName.setFocused(true);
    }

    private void closeCracked() {
        crackedOpen = false;
        crackedName.setFocused(false);
    }

    private void addCracked() {
        String name = crackedName.text().strip();
        if (!Accounts.isValidName(name)) {
            setStatus("Names are 3 to 16 letters, numbers or _", true);
            crackedName.setFocused(true);
            return;
        }
        Accounts.Account account = Accounts.addCracked(name);
        closeCracked();
        setStatus("Switched to " + account.name() + " (offline servers only)", false);
    }

    private void startAdding() {
        int mine = ++attempt;
        adding = Adding.REQUESTING;
        code = null;
        Thread thread = new Thread(() -> signIn(mine), "Cyanite sign-in");
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs on its own thread: get a code, then wait for you to finish signing in with it. */
    private void signIn(int mine) {
        try {
            MicrosoftAuth.DeviceCode c = MicrosoftAuth.requestDeviceCode();
            if (attempt != mine) return;
            code = c;
            adding = Adding.WAITING;
            while (attempt == mine && System.currentTimeMillis() < c.expiresAt()) {
                Thread.sleep(c.interval() * 1000L);
                if (attempt != mine) return;
                MicrosoftAuth.MsTokens tokens = MicrosoftAuth.pollDeviceCode(c);
                if (tokens == null) continue;
                adding = Adding.SIGNING_IN;
                Accounts.addAndUse(tokens).whenComplete((account, error) -> Minecraft.getInstance().execute(() -> {
                    if (attempt != mine) return;
                    adding = Adding.NONE;
                    if (error != null) setStatus(Accounts.message(error), true);
                    else setStatus("Added and switched to " + account.name(), false);
                }));
                return;
            }
            if (attempt == mine) fail(mine, "The code expired, try again");
        } catch (MicrosoftAuth.AuthException e) {
            fail(mine, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fail(int mine, String message) {
        Minecraft.getInstance().execute(() -> {
            if (attempt != mine) return;
            adding = Adding.NONE;
            setStatus(message, true);
        });
    }

    private void cancelAdding() {
        attempt++;
        adding = Adding.NONE;
        code = null;
    }

    private void use(Accounts.Account account) {
        busy = true;
        status = "Signing in as " + account.name() + "…";
        statusError = false;
        Accounts.use(account).whenComplete((updated, error) -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (error != null) setStatus(account.name() + ": " + Accounts.message(error), true);
            else setStatus("Switched to " + updated.name() + (updated.cracked() ? " (offline servers only)" : ""), false);
        }));
    }

    private void useLauncher() {
        Accounts.useLauncher();
        setStatus("Switched back to " + Accounts.launcher().getName(), false);
    }

    private void remove(Accounts.Account account) {
        if (!account.uuid().equals(confirming)) {
            confirming = account.uuid();
            confirmLeft = CONFIRM_TIME;
            return;
        }
        confirming = null;
        Accounts.remove(account.uuid());
        setStatus("Removed " + account.name(), false);
    }

    private void setStatus(String text, boolean error) {
        status = text;
        statusError = error;
        statusLeft = STATUS_TIME;
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        if (!crackedOpen) return false;
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            addCracked();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            closeCracked();
            return true;
        }
        return crackedName.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return crackedOpen && crackedName.charTyped(event);
    }

    @Override
    boolean isTyping() {
        return crackedOpen && crackedName.isFocused();
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean focused = crackedOpen && crackedName.mouseClicked(mouseX, mouseY);
        if (button != 0) return focused;
        for (Button b : List.copyOf(buttons)) {
            if (inside(mouseX, mouseY, b.x, b.y, b.w, b.h)) {
                if (!b.key.startsWith("del:")) confirming = null;
                b.action.run();
                return true;
            }
        }
        confirming = null;
        return inside(mouseX, mouseY, x, y, W, height);
    }
}
