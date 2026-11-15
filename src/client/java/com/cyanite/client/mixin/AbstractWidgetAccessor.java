package com.cyanite.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The title screen's stand-in buttons copy the tooltip of the button they press. */
@Mixin(AbstractWidget.class)
public interface AbstractWidgetAccessor {
    @Accessor("tooltip")
    WidgetTooltipHolder cyanite$tooltip();
}
