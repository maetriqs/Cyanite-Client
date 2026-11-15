package com.cyanite.client.mixin;

import com.cyanite.client.system.HeadlessMode;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Started by Cyanite Miner: the window is made hidden and stays that way. Startup code, so only HeadlessMode is asked. */
@Mixin(Window.class)
public class WindowMixin {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
    private void cyanite$hiddenForMiner(CallbackInfo ci) {
        if (HeadlessMode.on()) GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    }
}
