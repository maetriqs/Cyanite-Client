package com.cyanite.client.modules.world;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class AutoTool extends Module {
    private final BoolSetting switchBack = add(new BoolSetting("Switch back", true));
    private final BoolSetting avoidBreaking = add(new BoolSetting("Save tools", true));

    private int previousSlot = -1;

    public AutoTool() {
        super(Category.WORLD, "Auto Tool", "Switches to the best tool while mining");
    }

    @Override
    public void onTick() {
        // Area Miner picks between your drill and your edge tool itself, and Bedrock Burrow keeps the drill out of it.
        AreaMiner areaMiner = Modules.get(AreaMiner.class);
        BedrockBurrow burrow = Modules.get(BedrockBurrow.class);
        AutoMine autoMine = Modules.get(AutoMine.class);
        if (areaMiner != null && areaMiner.managesTools() || burrow != null && burrow.managesTools()
            || autoMine != null && autoMine.managesTools()) {
            previousSlot = -1;
            return;
        }
        Inventory inventory = mc.player.getInventory();

        if (!mc.gameMode.isDestroying() || !(mc.hitResult instanceof BlockHitResult hit)) {
            if (switchBack.get() && previousSlot >= 0) inventory.setSelectedSlot(previousSlot);
            previousSlot = -1;
            return;
        }

        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        int best = inventory.getSelectedSlot();
        float bestScore = score(inventory.getItem(best), state);

        for (int slot = 0; slot < 9; slot++) {
            float score = score(inventory.getItem(slot), state);
            if (score > bestScore) {
                best = slot;
                bestScore = score;
            }
        }

        if (best != inventory.getSelectedSlot()) {
            if (previousSlot < 0) previousSlot = inventory.getSelectedSlot();
            inventory.setSelectedSlot(best);
        }
    }

    private float score(ItemStack stack, BlockState state) {
        if (avoidBreaking.get() && stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 2) return -1;

        float speed = stack.getDestroySpeed(state);
        // A tool that actually gets the drop beats a faster one that doesn't.
        if (state.requiresCorrectToolForDrops() && stack.isCorrectToolForDrops(state)) speed += 1000;
        return speed;
    }
}
