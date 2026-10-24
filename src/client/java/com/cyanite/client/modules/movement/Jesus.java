package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

/** Makes the surface of liquids solid for you. Hooked in from LiquidBlockMixin. */
public class Jesus extends Module {
    private final BoolSetting lava = add(new BoolSetting("Lava", false));

    public Jesus() {
        super(Category.MOVEMENT, "Jesus", "Walk on water");
    }

    /** Whether this liquid block should act like a full block for this collision check. */
    public boolean walkable(BlockState state, BlockPos pos, CollisionContext context) {
        if (!isEnabled() || mc.player == null) return false;
        // Only the local player stands on it; everything else, including the server's copy of you, sinks as usual.
        if (!(context instanceof EntityCollisionContext entityContext) || entityContext.getEntity() != mc.player) return false;
        if (mc.player.isShiftKeyDown() || mc.player.isInWater() || mc.player.isInLava()) return false;
        if (state.getFluidState().is(FluidTags.LAVA) && !lava.get()) return false;

        // Only from above, so you can still swim out sideways or from below.
        return context.isAbove(Shapes.block(), pos, false);
    }
}
