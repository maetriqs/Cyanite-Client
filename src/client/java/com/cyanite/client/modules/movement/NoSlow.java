package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;

/** Walk at full speed while eating, drinking, blocking or drawing a bow. Hooked in from LocalPlayerMixin. */
public class NoSlow extends Module {
    public NoSlow() {
        super(Category.MOVEMENT, "No Slow", "Stops items slowing you down");
    }
}
