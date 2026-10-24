package com.cyanite.client;

import com.cyanite.client.account.Accounts;
import com.cyanite.client.config.Config;
import com.cyanite.client.config.Profiles;
import com.cyanite.client.donut.DonutSmp;
import com.cyanite.client.gui.ClickGuiScreen;
import com.cyanite.client.hud.Hud;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.modules.donut.BountyBalance;
import com.cyanite.client.modules.donut.FakePay;
import com.cyanite.client.modules.misc.AntiVanish;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.system.Keys;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.Updater;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class CyaniteClientMod implements ClientModInitializer {
    private static KeyMapping clickGuiKey;
    /** Ticks the menu key's been held since it was pressed, waiting to see if I comes too; -1 for none. */
    private static int menuWait = -1;
    private static final int MENU_HOLD = 5;

    public static KeyMapping clickGuiKey() {
        return clickGuiKey;
    }

    @Override
    public void onInitializeClient() {
        Modules.init();
        Profiles.installDefaults();
        Config.load();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            Config.save();
            Updater.applyOnExit();
        });
        // Once the game is up, so the launcher's account is known and can always be switched back to.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Accounts.init();
            Updater.start();
        });
        // The menu key works on the title screen and server list too, so accounts and settings can be
        // changed before joining. A key rather than a button, so nothing crowds other clients' buttons.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof TitleScreen) && !(screen instanceof JoinMultiplayerScreen)) return;
            ScreenKeyboardEvents.afterKeyPress(screen).register((s, event) -> {
                if (clickGuiKey.matches(event) && client.screen == s && !Stealth.active()) client.setScreen(ClickGuiScreen.open(s));
            });
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("cyanite_client", "hud"), Hud::render);
        WorldRenderEvents.END_MAIN.register(Render3D::render);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> DonutSmp.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DonutSmp.onDisconnect();
            Macros.stop();
        });
        // "Wait for chat" steps listen to both player chat and server messages.
        // Anti Vanish reads them too, to tell a real leave message from a silent vanish.
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, at) -> {
            Macros.onChat(message.getString());
            AntiVanish.onChat(message.getString());
        });
        // Streamer Mode holds back messages and commands with your base's coordinates in them.
        ClientSendMessageEvents.ALLOW_CHAT.register(StreamerMode::allowOutgoing);
        ClientSendMessageEvents.ALLOW_COMMAND.register(StreamerMode::allowOutgoing);
        // Fake Pay cancels /pay and intercepts /bal for own balance.
        ClientSendMessageEvents.ALLOW_COMMAND.register(FakePay::allowCommand);
        // Bounty & Balance's answers to its own /bal and /bounty are kept out of chat (and the
        // action bar, through GuiMixin). Ones you typed yourself are left alone.
        ClientSendMessageEvents.COMMAND.register(BountyBalance::onCommand);
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || BountyBalance.onServerMessage(message));
        // Fake Pay keeps its own /bal's answer out of chat, and puts fake amounts in the /bal answers you ask for.
        // (Action-bar copies of them go through GuiMixin.)
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || FakePay.allowChat(message));
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : FakePay.modifyChat(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            com.cyanite.client.modules.donut.AuctionBuyer.onServerMessage(message.getString());
            Macros.onChat(message.getString());
            AntiVanish.onChat(message.getString());
        });

        clickGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.cyanite_client.click_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_INSERT,
            KeyMapping.Category.MISC
        ));

        // The shared chunk scan: told when chunks come and go, handing its results back each tick.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> com.cyanite.client.system.ChunkScanner.chunkLoaded(chunk));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> com.cyanite.client.system.ChunkScanner.chunkUnloaded(chunk.getPos()));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            com.cyanite.client.system.ModCheckGuard.reset();
            com.cyanite.client.system.ChunkScanner.tick();
            Modules.tick();
            Macros.tick();
            // The menu opens once the key's let go or held a moment, so menu key + I (Stealth Mode)
            // never flashes the menu up first. While hidden the key does nothing at all.
            while (clickGuiKey.consumeClick()) {
                if (client.screen == null && !Stealth.active()) menuWait = 0;
            }
            if (Stealth.takeSwallowedMenu() || Stealth.active() || client.screen != null) menuWait = -1;
            if (menuWait >= 0 && (!Keys.isHeld(clickGuiKey) || ++menuWait > MENU_HOLD)) {
                menuWait = -1;
                client.setScreen(ClickGuiScreen.open(null));
            }
        });
    }
}
