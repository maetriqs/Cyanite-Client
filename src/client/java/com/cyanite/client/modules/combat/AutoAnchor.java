package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The anchor combo in one go: put a respawn anchor down and it's charged with glowstone from your
 * hotbar, then you swap to a totem (so you're holding one when it goes off) and blow it up. Anchors
 * only explode outside the Nether, so it does nothing there.
 */
public class AutoAnchor extends Module {
    private final SliderSetting charges = add(new SliderSetting("Charges", 1, 1, 4, 1));
    private final SliderSetting delay = add(new SliderSetting("Delay", 1, 0, 6, 1).format(v -> (int) (double) v + " ticks"));
    private final BoolSetting switchBack = add(new BoolSetting("Switch back", false));

    private BlockPos anchor;
    private int step;
    private int wait;
    private int tries;
    private int startSlot;

    public AutoAnchor() {
        super(Category.COMBAT, "Auto Anchor", "Place a respawn anchor: it's charged with glowstone, you swap to a totem and it blows");
    }

    @Override
    protected void onDisable() {
        anchor = null;
    }

    /** You're about to place something by clicking this (the use hook, before it happens). */
    public static void onUse(InteractionHand hand, BlockHitResult hit) {
        AutoAnchor self = Modules.get(AutoAnchor.class);
        if (self == null || !self.isEnabled() || self.anchor != null || mc.player == null) return;
        if (!mc.player.getItemInHand(hand).is(Items.RESPAWN_ANCHOR)) return;
        BlockPos clicked = hit.getBlockPos();
        BlockState there = mc.level.getBlockState(clicked);
        self.anchor = there.canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
        self.step = 1;
        self.tries = 0;
        self.wait = self.delay.get().intValue();
        self.startSlot = mc.player.getInventory().getSelectedSlot();
    }

    @Override
    public void onTick() {
        if (anchor == null) return;
        if (wait > 0) {
            wait--;
            return;
        }
        BlockState state = mc.level.getBlockState(anchor);
        if (!state.is(Blocks.RESPAWN_ANCHOR)) {
            // Not there yet (or it didn't go down): give it a few ticks, then give up.
            if (++tries > 5) anchor = null;
            return;
        }
        // In the Nether an anchor sets your spawn instead of blowing up.
        if (mc.level.dimension() == Level.NETHER || mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(anchor)) > 6) {
            anchor = null;
            return;
        }
        if (step == 1) {
            if (state.getValue(RespawnAnchorBlock.CHARGE) >= charges.get().intValue()) {
                step = 2;
            } else {
                int glowstone = hotbar(Items.GLOWSTONE);
                if (glowstone < 0) {
                    Notify.send("Auto Anchor", "No glowstone in your hotbar", false, false, false);
                    anchor = null;
                    return;
                }
                mc.player.getInventory().setSelectedSlot(glowstone);
                use(anchor);
                wait = delay.get().intValue();
                return;
            }
        }
        // Blow it: a totem in hand if there's one in the hotbar, else anything that isn't glowstone.
        int totem = hotbar(Items.TOTEM_OF_UNDYING);
        if (totem < 0) totem = notGlowstone();
        if (totem < 0) {
            anchor = null;
            return;
        }
        mc.player.getInventory().setSelectedSlot(totem);
        use(anchor);
        if (switchBack.get()) mc.player.getInventory().setSelectedSlot(startSlot);
        anchor = null;
    }

    private static void use(BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private static int hotbar(Item item) {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    private static int notGlowstone() {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        for (int i = 0; i < 9; i++) {
            var stack = mc.player.getInventory().getItem(i);
            if (!stack.is(Items.GLOWSTONE) && !stack.is(Items.RESPAWN_ANCHOR)) return i;
        }
        return -1;
    }
}
