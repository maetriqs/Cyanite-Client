package com.cyanite.client.visuals;

import com.cyanite.client.modules.render.NoHurtCam;
import com.cyanite.client.render.CameraShot;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/** How your view moves when you're hurt: the game's tilt, a bounce, a short shake, or nothing at all. */
public final class HurtCamera {
    public static final Group GROUP = new Group("hurt_camera", "Hurt Camera",
        "How your view moves when you take damage: tilt, bounce, shake or none, and how hard.",
        "hurt camera shake screen tilt damage bob");
    static final BoolSetting SHAKE = GROUP.add(new BoolSetting("Screen shake", true));
    static final ModeSetting STYLE = GROUP.add(new ModeSetting("Style", "Tilt", List.of("Tilt", "Bounce", "Shake")));
    static final SliderSetting INTENSITY = GROUP.add(new SliderSetting("Intensity", 0.6, 0, 2, 0.05).percent());

    private HurtCamera() {
    }

    /** In place of the game's bobHurt: true when it's been done here. */
    public static boolean bobHurt(PoseStack pose, float partial) {
        if (!GROUP.on() || Modules.get(NoHurtCam.class).isEnabled() || CameraShot.taking()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.getCameraEntity() instanceof LivingEntity e)) return false;
        // The roll as you die stays as it is.
        if (e.isDeadOrDying()) {
            float h = Math.min(e.deathTime + partial, 20);
            pose.mulPose(Axis.ZP.rotationDegrees(40 - 8000 / (h + 200)));
        }
        if (!SHAKE.get()) return true;
        float t = e.hurtTime - partial;
        if (t < 0 || e.hurtDuration <= 0) return true;
        float g = t / e.hurtDuration;
        float k = (float) (INTENSITY.get() * mc.options.damageTiltStrength().get());
        if (k <= 0) return true;
        switch (STYLE.get()) {
            case "Bounce" -> {
                float s = Mth.sin(g * g * Mth.PI);
                pose.translate(0, -s * 0.09f * k, 0);
                pose.mulPose(Axis.XP.rotationDegrees(s * 5 * k));
            }
            case "Shake" -> {
                float amp = g * g * k;
                pose.mulPose(Axis.ZP.rotationDegrees(Mth.sin(t * 5.3f) * 5 * amp));
                pose.mulPose(Axis.XP.rotationDegrees(Mth.cos(t * 7.1f) * 2.5f * amp));
                pose.translate(Mth.sin(t * 6.7f) * 0.03f * amp, Mth.cos(t * 4.9f) * 0.03f * amp, 0);
            }
            default -> {
                float s = Mth.sin(g * g * g * g * Mth.PI);
                float dir = e.getHurtDir();
                pose.mulPose(Axis.YP.rotationDegrees(-dir));
                pose.mulPose(Axis.ZP.rotationDegrees(-s * 14 * k));
                pose.mulPose(Axis.YP.rotationDegrees(dir));
            }
        }
        return true;
    }
}
