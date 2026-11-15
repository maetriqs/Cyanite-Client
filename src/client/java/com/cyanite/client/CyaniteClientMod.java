package com.cyanite.client;

import com.cyanite.client.account.Accounts;
import com.cyanite.client.config.Config;
import com.cyanite.client.config.Imports;
import com.cyanite.client.config.Profiles;
import com.cyanite.client.donut.DonutSmp;
import com.cyanite.client.gui.ClickGuiScreen;
import com.cyanite.client.gui.Home;
import com.cyanite.client.gui.HomeDisconnect;
import com.cyanite.client.gui.HomeServers;
import com.cyanite.client.gui.ImportScreen;
import com.cyanite.client.gui.JarWarningScreen;
import com.cyanite.client.gui.SniperPanel;
import com.cyanite.client.hud.Hud;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.modules.donut.AuctionSniper;
import com.cyanite.client.modules.donut.BountyBalance;
import com.cyanite.client.modules.donut.FakePay;
import com.cyanite.client.modules.misc.AntiVanish;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.system.Headless;
import com.cyanite.client.system.JarCheck;
import com.cyanite.client.system.Keys;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Online;
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
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class CyaniteClientMod implements ClientModInitializer {
    private static KeyMapping clickGuiKey;
    /** Ticks the menu key's been held since it was pressed, waiting to see if I comes too; -1 for none. */
    private static int menuWait = -1;
    private static final int MENU_HOLD = 5;
    /** Whether this session's first title screen has been checked for a Meteor setup to offer. */
    private static boolean importChecked;

    public static KeyMapping clickGuiKey() {
        return clickGuiKey;
    }

    @Override
    public void onInitializeClient() {
        Modules.init();
        Profiles.installDefaults();
        Config.load();
        com.cyanite.client.visuals.Visuals.init();
        Headless.init();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            Config.save();
            Updater.applyOnExit();
            Online.stop();
            com.cyanite.client.hud.SubwaySurfers.shutdown();
        });
        // Once the game is up, so the launcher's account is known and can always be switched back to.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Accounts.init();
            // Cyanite Miner brings its own jar and keeps it current itself.
            if (!Headless.active()) {
                Updater.start();
                JarCheck.start();
                Online.start();
            }
            Config.reportBroken();
        });
        // The menu key works on the title screen and server list too, so accounts and settings can be
        // changed before joining. A key rather than a button, so nothing crowds other clients' buttons.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof TitleScreen) && !(screen instanceof JoinMultiplayerScreen)) return;
            ScreenKeyboardEvents.afterKeyPress(screen).register((s, event) -> {
                if (clickGuiKey.matches(event) && client.screen == s && !Stealth.active()) client.setScreen(ClickGuiScreen.open(s));
            });
        });
        // The disconnected screen and the server list in Cyanite's look.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof DisconnectedScreen disconnected) HomeDisconnect.init(disconnected);
            else if (screen instanceof JoinMultiplayerScreen servers) HomeServers.init(servers);
        });
        // Auction Sniper's panel, beside the auction house menu.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof AbstractContainerScreen<?> menu && AuctionSniper.isAuctionTitle(menu.getTitle().getString())) SniperPanel.attach(menu);
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("cyanite_client", "hud"), Hud::render);
        WorldRenderEvents.END_MAIN.register(Render3D::render);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            DonutSmp.onJoin();
            Home.joined(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DonutSmp.onDisconnect();
            Macros.stop();
            // Whatever got switched while playing is on disk before the next server, or a crash in the menus.
            Config.saveSoon();
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
            // The auction house answers in the action bar as well as chat ("This item was already bought").
            com.cyanite.client.modules.donut.AuctionBuyer.onServerMessage(message.getString());
            AuctionSniper.onServerMessage(message.getString());
            if (overlay) return;
            // Chat only: the same sale in the action bar would be counted twice.
            com.cyanite.client.modules.donut.SellMenu.onServerMessage(message.getString());
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

        // Modules that attack, use or place tick here, before the movement packet, like your own clicks.
        ClientTickEvents.START_CLIENT_TICK.register(client -> Modules.preTick());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            com.cyanite.client.system.ModCheckGuard.reset();
            com.cyanite.client.system.ChunkScanner.tick();
            // Before the modules, so one coming back after a dropped connection knows it's back.
            com.cyanite.client.system.Rejoin.tick();
            Modules.tick();
            Macros.tick();
            Config.tick();
            // The jar isn't the one on GitHub: said on the title screen, before anything else is offered.
            if (client.getOverlay() == null && client.screen instanceof TitleScreen title && !Stealth.active()) {
                JarCheck.Result bad = JarCheck.takeWarning();
                if (bad != null) client.setScreen(new JarWarningScreen(title, bad));
            }
            // The first title screen: if another client's settings or waypoints are here and it's never been asked, offer to copy them.
            if (!importChecked && client.getOverlay() == null && client.screen instanceof TitleScreen title) {
                importChecked = true;
                if (!Stealth.active()) {
                    var found = Imports.found(false);
                    if (!found.isEmpty()) client.setScreen(new ImportScreen(title, found));
                }
            }
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

        // From here the settings are loaded: the loading and title screens can take Cyanite's look.
        Home.ready();
    }
}
