package com.cyanite.client.visuals;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

/** Your hits and swings, handed to the Visuals that answer them. */
public final class Combat {
    private static long attackedAt;
    /** The last thing you hit, when, and whether it was a crit (falling, not on a ladder or in water). */
    static Entity lastTarget;
    static long lastHitAt;
    static boolean lastCrit;

    private Combat() {
    }

    /** You attacked {@code target} (MultiPlayerGameMode.attack). */
    public static void attacked(Entity target) {
        attackedAt = System.nanoTime();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        lastTarget = target;
        lastHitAt = attackedAt;
        lastCrit = player.fallDistance > 0 && !player.onGround() && !player.onClimbable() && !player.isInWater()
            && !player.isPassenger() && player.getAttackStrengthScale(0.5f) > 0.9f;
        if (!Visuals.live()) return;
        Hands.onAttack(true);
        HitParticles.onAttack(target);
        HitColor.onAttack();
    }

    /** Your main hand swung; a swing straight after an attack is that attack's. */
    public static void swung() {
        if ((System.nanoTime() - attackedAt) / 1e6 < 60) return;
        if (Visuals.live()) Hands.onAttack(false);
    }

    /** Whether you hit {@code entity} in the last {@code seconds}. */
    static boolean hitRecently(Entity entity, double seconds) {
        return entity == lastTarget && (System.nanoTime() - lastHitAt) / 1e9 < seconds;
    }
}
