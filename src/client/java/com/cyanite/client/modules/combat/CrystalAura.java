package com.cyanite.client.modules.combat;

import com.cyanite.client.mixin.LocalPlayerAccessor;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Targets;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

/**
 * Places end crystals on obsidian or bedrock near a player and blows them up, picking the spot that
 * hurts them most, never one that would hurt you past the limit. Like Kill Aura it turns first and
 * places or hits once the server has that look, so each one is what a real click there would send.
 * Damage is worked out the way the server does it: distance, how much of them the blast sees,
 * difficulty, armor, Resistance, and Protection and Blast Protection on the armor they wear.
 */
public class CrystalAura extends Module {
    private static final float POWER = 6;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final SliderSetting placeRange = add(new SliderSetting("Place range", 4.5, 2, 6, 0.1));
    private final SliderSetting breakRange = add(new SliderSetting("Break range", 3, 2, 6, 0.1));
    private final SliderSetting targetRange = add(new SliderSetting("Target range", 10, 4, 16, 1));
    private final SliderSetting minDamage = add(new SliderSetting("Min damage", 6, 0, 20, 0.5));
    private final SliderSetting maxSelf = add(new SliderSetting("Max self damage", 8, 0, 20, 0.5));
    private final BoolSetting antiSuicide = add(new BoolSetting("Anti suicide", true));
    private final BoolSetting place = add(new BoolSetting("Place", true));
    private final BoolSetting breakCrystals = add(new BoolSetting("Break", true));
    private final SliderSetting delay = add(new SliderSetting("Delay", 2, 0, 10, 1).format(v -> (int) v + " ticks"));
    private final BoolSetting autoSwitch = add(new BoolSetting("Auto switch", true));
    private final BoolSetting hostiles = add(new BoolSetting("Hostiles", false));
    private final BoolSetting rotate = add(new BoolSetting("Rotate", true));
    private final BoolSetting pauseOnUse = add(new BoolSetting("Pause while eating", true));

    private static int lastJob = -100;
    private int wait;
    private LivingEntity target;

    public CrystalAura() {
        super(Category.COMBAT, "Crystal Aura", "Places and blows up end crystals next to players");
    }

    /** Whether it turned you toward a crystal or a spot this tick or last; Kill Aura holds off then. */
    public static boolean busy() {
        CrystalAura aura = Modules.get(CrystalAura.class);
        if (aura == null || !aura.isEnabled() || mc.player == null) return false;
        // A new player (respawned, rejoined) counts its ticks from 0 again.
        int since = mc.player.tickCount - lastJob;
        return since >= 0 && since <= 1;
    }

    @Override
    public String getInfo() {
        return target != null ? target.getName().getString() : null;
    }

    @Override
    protected boolean ticksEarly() {
        return true;
    }

    @Override
    protected void onDisable() {
        target = null;
    }

    @Override
    public void onTick() {
        if (wait > 0) wait--;
        target = null;
        if (mc.screen != null || ThrowAtFeet.busy()) return;
        if (pauseOnUse.get() && (mc.player.isUsingItem() || AutoGap.busy())) return;
        target = findTarget();
        if (target == null) return;

        // A crystal already standing is damage now, so hitting one comes before placing another.
        EndCrystal crystal = breakCrystals.get() ? bestCrystal() : null;
        if (crystal != null) {
            lastJob = mc.player.tickCount;
            AABB box = crystal.getBoundingBox();
            boolean onIt = !rotate.get() || Targets.sentLookHits(box, breakRange.get());
            if (rotate.get()) look(box.getCenter());
            if (!onIt || wait > 0) return;
            mc.gameMode.attack(mc.player, crystal);
            mc.player.swing(InteractionHand.MAIN_HAND);
            wait = delay.get().intValue();
            return;
        }

        if (!place.get()) return;
        InteractionHand hand;
        int slot = -1;
        if (mc.player.getOffhandItem().is(Items.END_CRYSTAL)) hand = InteractionHand.OFF_HAND;
        else if (mc.player.getMainHandItem().is(Items.END_CRYSTAL)) hand = InteractionHand.MAIN_HAND;
        else if (autoSwitch.get() && (slot = crystalSlot()) >= 0) hand = InteractionHand.MAIN_HAND;
        else return;

        BlockPos spot = bestSpot();
        if (spot == null) return;
        lastJob = mc.player.tickCount;
        // The middle of its top when you're above it: a look at the middle of the block would cross the
        // top of the one in front first. From below, the middle of the side you see.
        Vec3 aim = mc.player.getEyeY() > spot.getY() + 1 ? Vec3.upFromBottomCenterOf(spot, 1) : Vec3.atCenterOf(spot);
        BlockHitResult hit = rotate.get() ? sentHit(spot) : new BlockHitResult(Vec3.upFromBottomCenterOf(spot, 1), Direction.UP, spot, false);
        if (rotate.get()) look(aim);
        if (hit == null || wait > 0) return;
        if (slot >= 0) mc.player.getInventory().setSelectedSlot(slot);
        mc.gameMode.useItemOn(mc.player, hand, hit);
        mc.player.swing(hand);
        wait = delay.get().intValue();
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestDistance = targetRange.get();
        for (Entity entity : WorldScan.entities()) {
            if (!(entity instanceof LivingEntity living) || !Targets.isTarget(living, true, hostiles.get(), false)) continue;
            double distance = living.distanceTo(mc.player);
            if (distance < bestDistance) {
                best = living;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The crystal in reach whose blast does the target the most harm and you no more than allowed. */
    private EndCrystal bestCrystal() {
        EndCrystal best = null;
        float bestDamage = 0;
        for (Entity entity : WorldScan.entities()) {
            if (!(entity instanceof EndCrystal crystal) || !crystal.isAlive() || Targets.reach(crystal) > breakRange.get()) continue;
            float damage = worth(crystal.position());
            if (damage > bestDamage) {
                best = crystal;
                bestDamage = damage;
            }
        }
        return best;
    }

    /** The obsidian or bedrock in reach that a crystal can go on, be hit from where you stand, and do the most harm from. */
    private BlockPos bestSpot() {
        Vec3 eyes = mc.player.getEyePosition();
        double range = placeRange.get();
        int r = Mth.ceil(range);
        BlockPos centre = BlockPos.containing(eyes);
        BlockPos best = null;
        float bestDamage = 0;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
            if (nearest(new AABB(pos), eyes).distanceTo(eyes) > range) continue;
            BlockState state = mc.level.getBlockState(pos);
            if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) continue;
            BlockPos above = pos.above();
            if (!mc.level.isEmptyBlock(above)) continue;
            if (!mc.level.getEntities(null, new AABB(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1)).isEmpty()) continue;
            // The crystal it would be: two blocks wide and tall, standing on the block's top.
            AABB crystal = new AABB(pos.getX() - 0.5, pos.getY() + 1, pos.getZ() - 0.5, pos.getX() + 1.5, pos.getY() + 3, pos.getZ() + 1.5);
            if (nearest(crystal, eyes).distanceTo(eyes) > breakRange.get()) continue;
            float damage = worth(new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5));
            if (damage > bestDamage) {
                best = pos.immutable();
                bestDamage = damage;
            }
        }
        return best;
    }

    /** What a blast at {@code at} does to the target, or 0 when it's too little or too much for you. */
    private float worth(Vec3 at) {
        // The quick check first: even fully seen, it wouldn't be enough.
        if (target.distanceToSqr(at) > 144) return 0;
        float toTarget = damage(at, target);
        if (toTarget < minDamage.get()) return 0;
        float toSelf = damage(at, mc.player);
        if (toSelf > maxSelf.get()) return 0;
        if (antiSuicide.get() && toSelf >= mc.player.getHealth() + mc.player.getAbsorptionAmount() - 1) return 0;
        return toTarget;
    }

    /** A crystal's blast at {@code at} on {@code entity}, as ServerExplosion and the damage code work it out. */
    static float damage(Vec3 at, LivingEntity entity) {
        float size = POWER * 2;
        double distance = Math.sqrt(entity.distanceToSqr(at)) / size;
        if (distance > 1) return 0;
        double exposure = (1 - distance) * ServerExplosion.getSeenPercent(at, entity);
        float damage = (float) ((exposure * exposure + exposure) / 2 * 7 * size + 1);
        if (entity instanceof Player) {
            damage = switch (entity.level().getDifficulty()) {
                case PEACEFUL -> 0;
                case EASY -> Math.min(damage / 2 + 1, damage);
                case NORMAL -> damage;
                case HARD -> damage * 1.5f;
            };
        }
        float armor = entity.getArmorValue();
        float toughness = (float) entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float blocked = Mth.clamp(armor - damage / (2 + toughness / 4), armor * 0.2f, 20f);
        damage *= 1 - blocked / 25;
        MobEffectInstance resistance = entity.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) damage *= Math.max(0, 1 - (resistance.getAmplifier() + 1) * 0.2f);
        return damage * (1 - protection(entity) / 25);
    }

    /** What their armor's enchantments take off a blast, as CombatRules counts it: Protection 1 a level, Blast Protection 2, at most 20. */
    private static float protection(LivingEntity entity) {
        int points = 0;
        for (EquipmentSlot slot : ARMOR) {
            for (Object2IntMap.Entry<Holder<Enchantment>> e : entity.getItemBySlot(slot).getEnchantments().entrySet()) {
                if (e.getKey().is(Enchantments.PROTECTION)) points += e.getIntValue();
                else if (e.getKey().is(Enchantments.BLAST_PROTECTION)) points += e.getIntValue() * 2;
            }
        }
        return Math.min(points, 20);
    }

    /** Where the look the server has for you meets {@code pos}, as a real click there would send it, or null. */
    private BlockHitResult sentHit(BlockPos pos) {
        Vec3 eyes = mc.player.getEyePosition();
        LocalPlayerAccessor sent = (LocalPlayerAccessor) mc.player;
        Vec3 look = Vec3.directionFromRotation(sent.cyanite$getSentPitch(), sent.cyanite$getSentYaw());
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, eyes.add(look.scale(placeRange.get() + 1)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) ? hit : null;
    }

    private void look(Vec3 point) {
        float[] wanted = Targets.rotationTo(point);
        Targets.turnTo(wanted[0], wanted[1]);
    }

    private int crystalSlot() {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).is(Items.END_CRYSTAL)) return i;
        return -1;
    }

    private static Vec3 nearest(AABB box, Vec3 point) {
        return new Vec3(Mth.clamp(point.x, box.minX, box.maxX), Mth.clamp(point.y, box.minY, box.maxY), Mth.clamp(point.z, box.minZ, box.maxZ));
    }
}
