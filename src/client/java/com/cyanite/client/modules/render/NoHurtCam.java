package com.cyanite.client.modules.render;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;

/** Stops the camera shaking when you take damage. Hooked in from GameRendererMixin. */
public class NoHurtCam extends Module {
    public NoHurtCam() {
        super(Category.RENDER, "No Hurt Cam", "Removes the damage camera shake");
    }
}
