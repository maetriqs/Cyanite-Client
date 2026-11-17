package com.cyanite.client.visuals;

import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** When a shield is knocked out (an axe hit): a burst, a ring and a word over whoever's shield it was. */
public final class ShieldBreak {
    public static final Group GROUP = new Group("shield_break", "Shield Break Effects",
        "A burst, ring and \"Shield broken\" over anyone whose shield gets disabled, and a flash when you did it.",
        "shield break disable axe stun effect");
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0xFFB02E).full());
    static final BoolSetting BURST = GROUP.add(new BoolSetting("Burst", true));
    static final SliderSetting AMOUNT = GROUP.add(new SliderSetting("Burst amount", 18, 4, 50, 1));
    static final BoolSetting RING = GROUP.add(new BoolSetting("Ring", true));
    static final BoolSetting WORD = GROUP.add(new BoolSetting("\"Shield broken\" text", true));
    static final BoolSetting FLASH = GROUP.add(new BoolSetting("Flash when you break one", true));

    private ShieldBreak() {
    }

    /** A sound was played somewhere (ClientPacketListener.handleSoundEvent). */
    public static void onSound(ClientboundSoundPacket packet) {
        if (!GROUP.on() || packet.getSound().value() != SoundEvents.SHIELD_BREAK.value()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // The game plays it right where the shield's holder stands.
        Vec3 at = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        LivingEntity holder = null;
        double best = 2.5 * 2.5;
        for (Entity e : WorldScan.entities()) {
            if (!(e instanceof LivingEntity living)) continue;
            double d = e.position().distanceToSqr(at);
            if (d < best) {
                best = d;
                holder = living;
            }
        }
        Vec3 center = holder != null ? holder.position() : at;
        double height = holder != null ? holder.getBbHeight() : 1.8;
        int rgb = COLOR.get();
        if (BURST.get()) {
            Particles.spawn(ParticleTypes.CRIT, center.x, center.y + height * 0.6, center.z, AMOUNT.get().intValue(), 0.4, rgb, 0.25);
            Particles.spawn(ParticleTypes.ELECTRIC_SPARK, center.x, center.y + height * 0.6, center.z, AMOUNT.get().intValue() / 2, 0.3, rgb, 0.2);
        }
        if (RING.get()) {
            WorldFx.ring(center.add(0, 0.05, 0), rgb, 0.4, 3.2, 0.6f, 3f);
            WorldFx.uprightRing(center.add(0, height * 0.55, 0), rgb, 0.3, 2.4, 0.45f, 2.5f);
            WorldFx.burst(center.add(0, height * 0.55, 0), rgb, 1.2, 0.45f);
        }
        if (WORD.get() && holder != mc.player) WorldFx.word(center.add(0, height + 0.45, 0), "Shield broken", 0xFF000000 | rgb, 0.028f, 1.2f);
        if (FLASH.get() && holder != null && Combat.hitRecently(holder, 1)) WorldFx.flash(rgb, 0.25f, 0.35f);
    }
}
