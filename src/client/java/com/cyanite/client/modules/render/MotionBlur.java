package com.cyanite.client.modules.render;

import com.cyanite.client.mixin.GameRendererAccessor;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.ModeSetting;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * Motion blur: each frame of the world is blended with the ones before it, so turning and moving
 * smear smoothly. Done before the HUD is drawn, so your HUD and menus stay sharp. A post effect of
 * Cyanite's own (assets/cyanite_client/post_effect), one for each strength.
 */
public class MotionBlur extends Module {
    private final ModeSetting strength = add(new ModeSetting("Strength", "Medium", List.of("Low", "Medium", "High")));

    public MotionBlur() {
        super(Category.RENDER, "Motion Blur", "Smooth blur when you turn and move; the HUD stays sharp");
    }

    @Override
    public String getInfo() {
        return strength.get();
    }

    /** Right after the world's drawn, before the HUD (the game renderer's hook). */
    public void apply() {
        if (!isEnabled() || mc.level == null) return;
        Identifier id = Identifier.fromNamespaceAndPath("cyanite_client", "motion_blur_" + strength.get().toLowerCase(Locale.ROOT));
        PostChain chain = mc.getShaderManager().getPostChain(id, LevelTargetBundle.MAIN_TARGETS);
        if (chain != null) chain.process(mc.getMainRenderTarget(), ((GameRendererAccessor) mc.gameRenderer).cyanite$resourcePool());
    }
}
