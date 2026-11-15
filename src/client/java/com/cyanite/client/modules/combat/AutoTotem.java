package com.cyanite.client.modules.combat;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.mixin.AbstractContainerScreenAccessor;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Random;

/**
 * Keeps a totem in your offhand. Instant: swapped in the moment it's gone. Inventory: the way you'd
 * do it yourself, so it looks real to a server or anyone watching: a moment to react, your inventory
 * opens, the mouse moves over to a totem, the offhand key swaps it in, a moment, and it closes. Every
 * delay is a little different each time. When opened: it never opens your inventory itself; open it
 * and, a moment later, a totem's swapped in (your mouse left alone), the inventory left open. With
 * No totem glow, the edges of the screen glow red while the offhand has none.
 */
public class AutoTotem extends Module {
    private static final int OFFHAND_BUTTON = 40;

    private final ModeSetting mode = add(new ModeSetting("Mode", "Always", List.of("Always", "Low health")));
    private final SliderSetting health = add(new SliderSetting("Health", 10, 1, 20, 1));
    private final SliderSetting delay = add(new SliderSetting("Delay", 0, 0, 10, 1).format(v -> (int) v + " ticks"));
    private final ModeSetting style = add(new ModeSetting("Style", "Instant", List.of("Instant", "Inventory", "When opened")));
    private final SliderSetting humanMin = add(new SliderSetting("Human delay min", 2, 1, 10, 1).format(v -> (int) (double) v + " ticks"));
    private final SliderSetting humanMax = add(new SliderSetting("Human delay max", 5, 1, 15, 1).format(v -> (int) (double) v + " ticks"));
    private final BoolSetting glow = add(new BoolSetting("No totem glow", true));

    private enum Step { IDLE, OPEN, MOVE, CLICK, CLOSE }

    private final Random random = new Random();
    private int cooldown;
    private int count;
    private Step step = Step.IDLE;
    private int wait;
    private boolean opened;
    private long moveStart;
    private int moveMs;
    private double fromX;
    private double fromY;
    private double toX;
    private double toY;

    public AutoTotem() {
        super(Category.COMBAT, "Auto Totem", "Keeps a totem in your offhand, instantly, through your inventory like you would, or once you open it; the screen glows red while it's empty");
    }

    @Override
    public String getInfo() {
        return Integer.toString(count);
    }

    @Override
    protected void onDisable() {
        if (step != Step.IDLE) finish();
    }

    @Override
    public void onTick() {
        Inventory inventory = mc.player.getInventory();

        count = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? 1 : 0;
        int found = -1;
        for (int i = 0; i < 36; i++) {
            if (!inventory.getItem(i).is(Items.TOTEM_OF_UNDYING)) continue;
            count++;
            if (found < 0) found = i;
        }

        if (step != Step.IDLE) {
            inventoryStep(found);
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (found < 0 || mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        // Auto Mine has bottles in the off hand for a moment, mending the drill; it puts this back after.
        com.cyanite.client.modules.world.AutoMine autoMine = com.cyanite.client.system.Modules.get(com.cyanite.client.modules.world.AutoMine.class);
        if (autoMine != null && autoMine.usesOffhand()) return;
        if (mode.is("Low health") && mc.player.getHealth() + mc.player.getAbsorptionAmount() > health.get()) return;
        // Clicks go to the player's own inventory menu, so don't fire while another container is open.
        if (mc.screen != null && !(mc.screen instanceof InventoryScreen)) return;

        // In creative nobody's watching how it's done (and the inventory there is another screen).
        if (style.is("When opened") && !mc.player.isCreative()) {
            // Only once you've opened your inventory yourself, a moment after.
            if (!(mc.screen instanceof InventoryScreen)) return;
            step = Step.OPEN;
            wait = human();
            return;
        }
        if (style.is("Inventory") && !mc.player.isCreative()) {
            step = Step.OPEN;
            wait = human();
            return;
        }
        mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, menuSlot(found), OFFHAND_BUTTON, ClickType.SWAP, mc.player);
        cooldown = delay.get().intValue();
    }

    /** Inventory slot -> slot index in the player's inventory menu (hotbar sits at 36-44). */
    private static int menuSlot(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    /** A reaction time, somewhere in the human range set. */
    private int human() {
        int lo = humanMin.get().intValue();
        int hi = Math.max(lo, humanMax.get().intValue());
        return lo + random.nextInt(hi - lo + 1);
    }

    private void inventoryStep(int found) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (step) {
            case OPEN -> {
                if (found < 0 || mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
                    finish();
                } else if (mc.screen == null && !style.is("When opened")) {
                    mc.setScreen(new InventoryScreen(mc.player));
                    opened = true;
                    step = Step.MOVE;
                    moveStart = 0;
                } else if (mc.screen instanceof InventoryScreen) {
                    opened = false;
                    // Your inventory, opened by you: the swap without taking your mouse.
                    step = style.is("When opened") ? Step.CLICK : Step.MOVE;
                    moveStart = 0;
                } else {
                    finish();
                }
            }
            case MOVE -> {
                // The mouse glides over in onFrame; if the screen went away or changed, stop.
                if (!(mc.screen instanceof InventoryScreen) || mc.player.isCreative()) finish();
            }
            case CLICK -> {
                if (!(mc.screen instanceof InventoryScreen) || found < 0) {
                    step = Step.CLOSE;
                    return;
                }
                // Something on the cursor: the offhand key does nothing then, so not till it's put down.
                if (!mc.player.inventoryMenu.getCarried().isEmpty()) return;
                // The offhand key over the totem: the swap a player does.
                mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, menuSlot(found), OFFHAND_BUTTON, ClickType.SWAP, mc.player);
                step = Step.CLOSE;
                wait = human();
            }
            case CLOSE -> {
                finish();
                cooldown = delay.get().intValue();
            }
            default -> finish();
        }
    }

    /** Done (or given up): whatever inventory it opened is closed again, whichever kind it's become. */
    private void finish() {
        if (opened && (mc.screen instanceof InventoryScreen || mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)) {
            mc.screen.onClose();
        }
        step = Step.IDLE;
        opened = false;
    }

    /** The screen's edges glowing red, a slow pulse, while there's no totem in your offhand. */
    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!glow.get() || mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || mc.player.isCreative() || mc.player.isSpectator()) return;
        // Auto Mine's bottles in the off hand for a moment, mending the drill: it puts the totem back.
        com.cyanite.client.modules.world.AutoMine autoMine = com.cyanite.client.system.Modules.get(com.cyanite.client.modules.world.AutoMine.class);
        if (autoMine != null && autoMine.usesOffhand()) return;
        // Render2D.pixels is the window's own pixels here.
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        // How far in it reaches, in steps of 16 pixels (each size is a texture of its own).
        int depth = Math.max(32, Math.min(w, h) / 6 / 16 * 16);
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 450.0);
        int alpha = (int) Math.round(255 * (0.4 + 0.2 * pulse));
        int red = alpha << 24 | 0xE01818;
        // A strip just off each edge, its glow reaching in; the corners, where two meet, glow most.
        int off = 8;
        Render2D.pixels(g);
        Render2D.glow(g, -off, -depth, off, h + 2 * depth, 0, depth, red);
        Render2D.glow(g, w, -depth, off, h + 2 * depth, 0, depth, red);
        Render2D.glow(g, 0, -off, w, off, 0, depth, red);
        Render2D.glow(g, 0, h, w, off, 0, depth, red);
        Render2D.end(g);
    }

    @Override
    public void onFrame() {
        if (step != Step.MOVE || !(mc.screen instanceof InventoryScreen screen)) return;
        int found = -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                found = i;
                break;
            }
        }
        if (found < 0) return;
        Slot slot = screen.getMenu().getSlot(menuSlot(found));
        AbstractContainerScreenAccessor at = (AbstractContainerScreenAccessor) screen;
        Window window = mc.getWindow();
        double toScreen = (double) window.getScreenWidth() / window.getGuiScaledWidth();
        long now = System.currentTimeMillis();
        if (moveStart == 0) {
            moveStart = now;
            moveMs = 110 + random.nextInt(140);
            fromX = mc.mouseHandler.xpos();
            fromY = mc.mouseHandler.ypos();
            // A little off the slot's middle, like a hand would land.
            toX = (at.cyanite$leftPos() + slot.x + 8 + random.nextInt(5) - 2) * toScreen;
            toY = (at.cyanite$topPos() + slot.y + 8 + random.nextInt(5) - 2) * toScreen;
        }
        double tx = toX;
        double ty = toY;
        double t = Math.min(1, (now - moveStart) / (double) moveMs);
        double e = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
        GLFW.glfwSetCursorPos(window.handle(), fromX + (tx - fromX) * e, fromY + (ty - fromY) * e);
        if (t >= 1) {
            step = Step.CLICK;
            wait = 1 + random.nextInt(2);
        }
    }
}
