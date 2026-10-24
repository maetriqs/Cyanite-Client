package com.cyanite.client.hud;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.setting.BoolSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class Watermark extends HudElement {
    private static final String NAME = "Cyanite";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final int GAP = 4;
    private static final int DOT_GAP = 9;

    private final BoolSetting version = add(new BoolSetting("Version", true));
    private final BoolSetting fps = add(new BoolSetting("FPS", true));
    private final BoolSetting ping = add(new BoolSetting("Ping", true));
    private final BoolSetting time = add(new BoolSetting("Time", false));
    private final BoolSetting background = add(new BoolSetting("Background", true));

    private final String versionText = FabricLoader.getInstance().getModContainer("cyanite_client")
        .map(mod -> "v" + mod.getMetadata().getVersion().getFriendlyString())
        .orElse("");
    private final List<String> extras = new ArrayList<>();

    public Watermark() {
        super("Watermark", true, Horizontal.LEFT, Vertical.TOP, 4, 4);
    }

    @Override
    public void update(float dt, boolean editing) {
        Minecraft mc = Minecraft.getInstance();
        extras.clear();

        if (fps.get()) extras.add(mc.getFps() + " fps");
        if (ping.get() && mc.player != null && mc.getConnection() != null && !mc.hasSingleplayerServer()) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) extras.add(info.getLatency() + " ms");
        }
        if (time.get()) extras.add(LocalTime.now().format(CLOCK));

        int textWidth = Text.width(NAME);
        if (version.get()) textWidth += GAP + Text.width(versionText);
        for (String extra : extras) textWidth += DOT_GAP + Text.width(extra);

        width = textWidth + (background.get() ? 12 : 0);
        height = Text.CAP + (background.get() ? 8 : 0);
    }

    @Override
    public void draw(GuiGraphics g, float alpha, boolean editing) {
        int s = Render2D.scale();
        int accent = Theme.accent();
        float glow = Theme.glow();
        boolean chip = background.get();
        boolean shadow = Hud.SHADOW.get() && !chip;
        int textX = x + (chip ? 6 : 0);
        int textY = y + (chip ? 4 : 0);
        int nameW = Text.width(NAME);

        Render2D.pixels(g);
        if (chip) {
            int r = Math.round(Theme.radius() * s);
            Render2D.glow(g, x * s, y * s + s, width * s, height * s, r, 7 * s, Theme.fade(Theme.SHADOW, alpha * 0.8f));
            Render2D.glow(g, x * s, y * s, width * s, height * s, r, 7 * s, Theme.alpha(accent, 45 * glow * alpha));
            Render2D.rect(g, x * s, y * s, width * s, height * s, r, Theme.fade(Theme.BORDER, alpha));
            Render2D.rect(g, x * s + 1, y * s + 1, width * s - 2, height * s - 2, Math.max(0, r - 1), Theme.fade(0xE8101217, alpha));

            int line = Math.max(1, s / 2);
            Render2D.fadeRight(g, x * s + 1, (y + height) * s - line - 1, width * s - 2, line, Theme.alpha(accent, 200 * alpha));
        }
        Render2D.glow(g, textX * s, textY * s, nameW * s, Text.CAP * s, 2 * s, 7 * s, Theme.alpha(accent, 50 * glow * alpha));
        Render2D.end(g);

        Text.draw(g, NAME, textX, textY, Theme.fade(Theme.accentText(), alpha), shadow);
        int cursor = textX + nameW;

        if (version.get()) {
            cursor += GAP;
            Text.draw(g, versionText, cursor, textY, Theme.fade(chip ? Theme.TEXT_FAINT : Theme.TEXT_DIM, alpha), shadow);
            cursor += Text.width(versionText);
        }

        for (String extra : extras) {
            int dot = Math.max(2, s);
            int dotX = (cursor + DOT_GAP / 2) * s - dot / 2;
            int dotY = textY * s + (Text.CAP * s - dot) / 2;
            Render2D.pixels(g);
            Render2D.rect(g, dotX, dotY, dot, dot, dot / 2, Theme.fade(Theme.TEXT_FAINT, alpha));
            Render2D.end(g);

            cursor += DOT_GAP;
            Text.draw(g, extra, cursor, textY, Theme.fade(Theme.TEXT_DIM, alpha), shadow);
            cursor += Text.width(extra);
        }
    }
}
