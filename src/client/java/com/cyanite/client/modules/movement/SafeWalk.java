package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;

/** Stops you walking off edges, like sneaking does, without slowing down. Hooked in from PlayerMixin. */
public class SafeWalk extends Module {
    public SafeWalk() {
        super(Category.MOVEMENT, "Safe Walk", "Keeps you from walking off edges");
    }
}
