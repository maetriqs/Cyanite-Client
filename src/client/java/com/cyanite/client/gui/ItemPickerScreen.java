package com.cyanite.client.gui;

import com.cyanite.client.system.setting.ItemListSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Picks the items for an item list: every item in the game in one searchable list, like the block picker. */
public class ItemPickerScreen extends PickerScreen<ItemPickerScreen.Entry> {
    record Entry(Item item, String name, String id, ItemStack icon) {
    }

    private static List<Entry> all;

    private final ItemListSetting setting;

    public ItemPickerScreen(ItemListSetting setting, Screen parent) {
        super("Pick items", parent);
        this.setting = setting;
    }

    @Override
    protected List<Entry> entries() {
        if (all == null) {
            List<Entry> list = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                ItemStack icon = new ItemStack(item);
                String id = BuiltInRegistries.ITEM.getKey(item).getPath();
                list.add(new Entry(item, icon.getHoverName().getString(), id.replace('_', ' '), icon));
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
        return setting.contains(e.item);
    }

    @Override
    protected void toggle(Entry e) {
        setting.toggle(e.item);
    }

    @Override
    protected int onCount() {
        return setting.get().size();
    }

    @Override
    protected String countText(int count) {
        return count + (count == 1 ? " item picked" : " items picked");
    }

    @Override
    protected void clearAll() {
        setting.clear();
    }

    @Override
    protected String placeholder() {
        return "Search items: totem, sword, crystal...";
    }

    @Override
    protected String emptyText() {
        return "No item called that";
    }

    @Override
    protected String hint() {
        return "Click an item to add or remove it   Esc goes back";
    }

    @Override
    protected String restHeading() {
        return "All items";
    }

    @Override
    protected int iconWidth() {
        return 15;
    }

    @Override
    protected void drawIcon(GuiGraphics g, Entry e, int x, int y) {
        g.pose().pushMatrix();
        g.pose().translate(x, y + 1.5f);
        g.pose().scale(0.75f);
        g.renderItem(e.icon, 0, 0);
        g.pose().popMatrix();
    }
}
