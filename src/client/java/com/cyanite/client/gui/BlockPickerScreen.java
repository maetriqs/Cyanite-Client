package com.cyanite.client.gui;

import com.cyanite.client.system.setting.BlockListSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the blocks for a block list, the way Meteor does it: every block in the game in one
 * searchable list, a click adding or removing one. With nothing typed, the ones already picked
 * sit at the top.
 */
public class BlockPickerScreen extends PickerScreen<BlockPickerScreen.Entry> {
    /** Every block, once: its name to show and search, and its item for the icon. */
    record Entry(Block block, String name, String id, ItemStack icon) {
    }

    private static List<Entry> all;

    private final BlockListSetting setting;

    public BlockPickerScreen(BlockListSetting setting, Screen parent) {
        super("Pick blocks", parent);
        this.setting = setting;
    }

    @Override
    protected List<Entry> entries() {
        if (all == null) {
            List<Entry> list = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block instanceof AirBlock) continue;
                // Wall signs, wall torches, wall banners...: the same item and name as the standing one,
                // which stands for both (Block ESP finds the wall kind too).
                if (block.asItem() instanceof BlockItem item && item.getBlock() != block) continue;
                ItemStack icon = block.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(block.asItem());
                String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
                list.add(new Entry(block, block.getName().getString(), id.replace('_', ' '), icon));
            }
            list.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
            all = List.copyOf(list);
        }
        return all;
    }

    @Override
    protected String name(Entry e) {
        return e.name;
    }

    @Override
    protected String searchText(Entry e) {
        return e.id;
    }

    @Override
    protected boolean isOn(Entry e) {
        return setting.contains(e.block);
    }

    @Override
    protected void toggle(Entry e) {
        setting.toggle(e.block);
    }

    @Override
    protected int onCount() {
        return setting.get().size();
    }

    @Override
    protected String countText(int count) {
        return count + (count == 1 ? " block picked" : " blocks picked");
    }

    @Override
    protected void clearAll() {
        setting.clear();
    }

    @Override
    protected String placeholder() {
        return "Search blocks: spawner, chest, diamond...";
    }

    @Override
    protected String emptyText() {
        return "No block called that";
    }

    @Override
    protected String hint() {
        return "Click a block to add or remove it   Esc goes back";
    }

    @Override
    protected String restHeading() {
        return "All blocks";
    }

    @Override
    protected int iconWidth() {
        return 15;
    }

    @Override
    protected void drawIcon(GuiGraphics g, Entry e, int x, int y) {
        if (e.icon.isEmpty()) return;
        g.pose().pushMatrix();
        g.pose().translate(x, y + 1.5f);
        g.pose().scale(0.75f);
        g.renderItem(e.icon, 0, 0);
        g.pose().popMatrix();
    }
}
