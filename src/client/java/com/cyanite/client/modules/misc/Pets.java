package com.cyanite.client.modules.misc;

import com.cyanite.client.mixin.MobInvoker;
import com.cyanite.client.modules.combat.AntiBot;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Pets that follow you around: real mob models that exist only on your screen. Nobody else sees
 * them and the server never hears of them. Up to three trot along behind you, keep out from under
 * your feet, and sit down when you stand still.
 *
 * <p>When you get too far ahead, or round a corner out of sight, they come straight to you: that's
 * also what brings them along after /rtp, a /home or a portal. They're made again in every world
 * you join, so a relog doesn't lose them. They never get in the crosshair's way or shove you.
 * While you glide on an elytra you hold them, tucked under you.
 *
 * <p>They react to what happens. Something found (a spawner, a base, villagers, diamonds or the
 * like turning up in your pockets) gets them bouncing and spinning in sparkles, the allay dancing
 * and the panda rolling. A kill or a level up gets a hop and hearts. A player coming close, someone
 * in vanish or a pearl landing by you has them up on their feet, staring at it (the armadillo
 * curls up). You getting hurt gets a tilted head, a whimper and a tear; dying, a sad sit.
 */
public class Pets extends Module {
    private static final List<String> KINDS = List.of("Wolf", "Cat", "Fox", "Parrot", "Allay", "Bee", "Axolotl", "Frog",
        "Rabbit", "Armadillo", "Panda", "Chicken");
    private static final Set<String> FLYING = Set.of("Parrot", "Allay", "Bee");
    private static final int SLOTS = 3;
    /** Ids far below anything a server hands out, so they never clash with a real entity. */
    private static final int FIRST_ID = -7_000_000;
    /** Further than this and a pet comes straight to you. */
    private static final double CATCH_UP = 12;
    /** Out of your sight this long and it comes round to you. */
    private static final long LOST_MS = 1500;
    private static final long SIT_AFTER_MS = 2500;
    /** Where on the ring round you each pet keeps to, so they don't pile up. */
    private static final double[] SPREAD = {0, 0.7, -0.7};
    /** Side by side when held. */
    private static final double[] HELD_SIDE = {0, 0.45, -0.45};
    /** A player this close gets the pets on guard. */
    private static final double PLAYER_ALERT = 24;
    /** Turning up in your inventory, these get the pets excited. */
    private static final Set<Item> VALUABLE = Set.of(Items.DIAMOND, Items.DIAMOND_ORE, Items.DEEPSLATE_DIAMOND_ORE, Items.DIAMOND_BLOCK,
        Items.EMERALD, Items.EMERALD_BLOCK, Items.ANCIENT_DEBRIS, Items.NETHERITE_SCRAP, Items.NETHERITE_INGOT, Items.NETHERITE_BLOCK,
        Items.SPAWNER, Items.ELYTRA, Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE, Items.NETHER_STAR, Items.BEACON,
        Items.HEAVY_CORE, Items.TRIDENT);

    /** How the pets take something that just happened, smallest first: a bigger one cuts in on a smaller one. */
    public enum Mood {
        /** A kill, a level up, a tunnel dug: a hop and hearts. */
        HAPPY(30),
        /** Found something: bouncing, a spin, sparkles. */
        EXCITED(50),
        /** You're hurt or low: a tilted head, a whimper, a tear. */
        WORRIED(50),
        /** Someone coming: on its feet, staring at them. */
        ALARMED(45),
        /** You died. */
        SAD(90);

        final int ticks;

        Mood(int ticks) {
            this.ticks = ticks;
        }
    }

    /** Reactions asked for from elsewhere (other modules' alerts), taken up on the next tick. */
    private static volatile Mood asked;
    private static volatile Entity attacked;
    private static volatile long attackedAt;

    private final ModeSetting first = add(new ModeSetting("Pet", "Wolf", KINDS));
    private final ModeSetting second = add(new ModeSetting("Second pet", "None", withNone()));
    private final ModeSetting third = add(new ModeSetting("Third pet", "None", withNone()));
    private final StringSetting names = add(new StringSetting("Names", "", 48).placeholder("e.g. Rex, Milo"));
    private final BoolSetting baby = add(new BoolSetting("Baby", true));
    private final SliderSetting distance = add(new SliderSetting("Distance", 2.5, 1.5, 6, 0.5)
        .format(v -> String.format(Locale.ROOT, "%.1f blocks", v)));
    private final BoolSetting sit = add(new BoolSetting("Sit when you stop", true));
    private final BoolSetting react = add(new BoolSetting("React", true));
    private final BoolSetting sounds = add(new BoolSetting("Sounds", true));
    private final BoolSetting hold = add(new BoolSetting("Hold when flying", true));

    private final Pet[] pets = new Pet[SLOTS];
    private final Random random = new Random();
    private int ticks;
    // What's been seen of you, to notice when it changes.
    private LocalPlayer watching;
    private float lastHealth;
    private int lastLevel;
    private boolean lowNoted;
    private boolean deathNoted;
    private long hurtAt;
    private Map<Item, Integer> valuables = new HashMap<>();
    private final Map<UUID, Long> playersNear = new HashMap<>();

    public Pets() {
        super(Category.MISC, "Pets", "Pets that follow you, only you can see them");
    }

    private static List<String> withNone() {
        List<String> out = new ArrayList<>();
        out.add("None");
        out.addAll(KINDS);
        return out;
    }

    /** One of these pets: kept out of the crosshair, out of fights and from pushing you. */
    public static boolean isPet(Entity entity) {
        int id = entity.getId();
        return id <= FIRST_ID && id > FIRST_ID - SLOTS;
    }

    @Override
    public String getInfo() {
        int count = 0;
        for (Pet pet : pets) if (pet != null) count++;
        return Integer.toString(count);
    }

    // Something to see that only this client shows: gone while Stealth Mode's on.
    @Override
    public boolean hiddenInStealth() {
        return true;
    }

    @Override
    protected void onDisable() {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (pets[slot] != null) remove(pets[slot]);
            pets[slot] = null;
        }
    }

    /** Something happened: every pet reacts to it. Safe to call from anywhere. */
    public static void react(Mood mood) {
        asked = mood;
    }

    /** A Cyanite alert went up: a find gets the pets excited, a threat puts them on guard. */
    public static void onNotify(String title, String body, boolean loud) {
        String t = title.toLowerCase(Locale.ROOT);
        String b = body.toLowerCase(Locale.ROOT);
        if (t.contains("found") || t.startsWith("possible base") || t.startsWith("auto mine") && b.contains("spawner")) react(Mood.EXCITED);
        else if (t.startsWith("pearl landing") || t.startsWith("relog now") || b.contains("came within") || loud && t.startsWith("anti vanish")) react(Mood.ALARMED);
        else if (t.equals("auto mine finished")) react(Mood.HAPPY);
        else if (t.equals("auto mine left") || t.equals("auto mine stopped") && b.contains("damage")) react(Mood.WORRIED);
    }

    /** You swung at something: if it's dead soon after, that's your kill. */
    public static void onAttack(Entity target) {
        attacked = target;
        attackedAt = System.currentTimeMillis();
    }

    @Override
    public void onTick() {
        LocalPlayer player = mc.player;
        ticks++;
        String[] kinds = {first.get(), second.get(), third.get()};
        String[] named = names.get().split(",");
        long now = System.currentTimeMillis();
        for (int slot = 0; slot < SLOTS; slot++) {
            Pet pet = pets[slot];
            String kind = kinds[slot];
            String name = slot < named.length ? named[slot].strip() : "";
            // Left behind in the old world (a relog, a portal), or changed in the settings: make it again.
            if (pet != null && (pet.entity.isRemoved() || pet.entity.level() != mc.level || !pet.kind.equals(kind)
                || !pet.name.equals(name) || pet.baby != baby.get())) {
                remove(pet);
                pet = null;
            }
            if (pet == null && !kind.equals("None")) pet = spawn(kind, name, slot, player);
            pets[slot] = pet;
            if (pet != null) {
                if (hold.get() && player.isFallFlying()) hold(pet, slot, player);
                else follow(pet, slot, player, now);
                feel(pet);
            }
        }
        Mood mood = asked;
        if (mood != null) {
            asked = null;
            if (react.get()) startMood(mood, null);
        }
        if (react.get()) watch(player, now);
    }

    /** Notices what happens to you: hurt, low, dead, a level up, a kill, something good picked up, a player coming. */
    private void watch(LocalPlayer player, long now) {
        if (watching != player) {
            // A new world, a respawn, or just switched on: start from how things are now.
            watching = player;
            lastHealth = player.getHealth();
            lastLevel = player.experienceLevel;
            valuables = valuables(player);
            lowNoted = player.getHealth() <= 6;
            deathNoted = false;
            playersNear.clear();
            return;
        }
        float health = player.getHealth();
        if (player.isDeadOrDying()) {
            if (!deathNoted) startMood(Mood.SAD, null);
            deathNoted = true;
            return;
        }
        if (health < lastHealth - 0.5f && now - hurtAt > 3000) {
            hurtAt = now;
            Entity mob = nearestHostile(player);
            startMood(mob != null ? Mood.ALARMED : Mood.WORRIED, mob);
        }
        if (health <= 6 && !lowNoted) startMood(Mood.WORRIED, null);
        lowNoted = health <= 6 || lowNoted && health <= 12;
        lastHealth = health;

        if (player.experienceLevel > lastLevel) startMood(Mood.HAPPY, null);
        lastLevel = player.experienceLevel;

        Map<Item, Integer> counts = valuables(player);
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (e.getValue() > valuables.getOrDefault(e.getKey(), 0)) {
                startMood(Mood.EXCITED, null);
                break;
            }
        }
        valuables = counts;

        Entity hit = attacked;
        if (hit != null) {
            if (now - attackedAt > 4000) attacked = null;
            else if (hit instanceof LivingEntity living && living.isDeadOrDying()) {
                startMood(Mood.HAPPY, hit);
                attacked = null;
            }
        }

        for (Player other : mc.level.players()) {
            if (other == player || other.isSpectator() || AntiBot.isBot(other) || other.distanceTo(player) > PLAYER_ALERT) continue;
            Long seen = playersNear.put(other.getUUID(), now);
            if (seen == null || now - seen > 60_000) startMood(Mood.ALARMED, other);
        }
    }

    private static Map<Item, Integer> valuables(LocalPlayer player) {
        Map<Item, Integer> out = new HashMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (VALUABLE.contains(stack.getItem())) out.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return out;
    }

    private Entity nearestHostile(LocalPlayer player) {
        Entity best = null;
        for (Entity e : mc.level.getEntities(player, player.getBoundingBox().inflate(8), e -> e instanceof Enemy && e.isAlive())) {
            if (best == null || e.distanceToSqr(player) < best.distanceToSqr(player)) best = e;
        }
        return best;
    }

    /** Every pet takes it in, a moment apart so they don't move as one; a bigger feeling still playing out isn't cut short. */
    private void startMood(Mood mood, Entity at) {
        for (Pet pet : pets) {
            if (pet == null) continue;
            if (pet.mood != null && pet.mood.ordinal() > mood.ordinal() && ticks - pet.moodStart <= pet.mood.ticks) continue;
            endMood(pet);
            pet.mood = mood;
            pet.moodStart = ticks + random.nextInt(6);
            pet.lookAt = at;
        }
    }

    /** How far into its reaction a pet is, or -1 if it isn't reacting (yet). */
    private int moodTick(Pet pet) {
        if (pet.mood == null) return -1;
        int t = ticks - pet.moodStart;
        if (t > pet.mood.ticks) {
            endMood(pet);
            return -1;
        }
        return t;
    }

    /** The particles, sounds and poses of a reaction, for this tick of it. */
    private void feel(Pet pet) {
        int t = moodTick(pet);
        if (t < 0) return;
        Entity e = pet.entity;
        switch (pet.mood) {
            case HAPPY -> {
                if (t % 10 == 0 && t <= 20) particles(e, ParticleTypes.HEART, 2);
                if (t == 0) sound(pet, 1.25f);
            }
            case EXCITED -> {
                if (t % 5 == 0 && t <= 40) particles(e, ParticleTypes.HAPPY_VILLAGER, 3);
                if (t == 0) mc.level.addParticle(ParticleTypes.NOTE, e.getX(), e.getY() + e.getBbHeight() + 0.5, e.getZ(), random.nextInt(25) / 24.0, 0, 0);
                if (t == 0 || t == 24) sound(pet, 1.45f);
                if (t == 0) {
                    if (e instanceof Allay allay) dance(allay, true);
                    if (e instanceof Panda panda) panda.roll(true);
                }
                if (t == 30 && e instanceof Panda panda) panda.roll(false);
            }
            case WORRIED -> {
                if (t == 0) {
                    interested(e, true);
                    sound(pet, 0.75f);
                }
                if (t % 12 == 0) tear(e);
            }
            case ALARMED -> {
                if (t == 0 || t == 20) particles(e, ParticleTypes.ANGRY_VILLAGER, 1);
                if (t == 0) sound(pet, 0.85f);
                if (e instanceof Armadillo armadillo) {
                    if (t == 0) armadillo.switchToState(Armadillo.ArmadilloState.ROLLING);
                    if (t == 10) armadillo.switchToState(Armadillo.ArmadilloState.SCARED);
                    if (t == 35) armadillo.switchToState(Armadillo.ArmadilloState.UNROLLING);
                }
            }
            case SAD -> {
                if (t == 0) {
                    interested(e, true);
                    if (e instanceof Cat cat) cat.setLying(true);
                }
                if (t % 15 == 0) tear(e);
            }
        }
    }

    /** Puts a pet back to normal after a reaction. */
    private void endMood(Pet pet) {
        if (pet.mood == null) return;
        Entity e = pet.entity;
        interested(e, false);
        if (e instanceof Allay allay && allay.isDancing()) dance(allay, false);
        if (e instanceof Panda panda) panda.roll(false);
        if (e instanceof Cat cat) cat.setLying(false);
        if (e instanceof Armadillo armadillo && pet.mood == Mood.ALARMED) armadillo.switchToState(Armadillo.ArmadilloState.IDLE);
        pet.mood = null;
        pet.lookAt = null;
    }

    /** A hop or a few: how high off the floor it is this tick of its reaction. */
    private static double hop(Mood mood, int t) {
        if (mood == null || t < 0) return 0;
        int[] starts = switch (mood) {
            case HAPPY, ALARMED -> new int[] {0};
            case EXCITED -> new int[] {0, 12, 24};
            default -> new int[0];
        };
        for (int s : starts) {
            if (t >= s && t < s + 8) return (mood == Mood.ALARMED ? 0.3 : 0.5) * Math.sin(Math.PI * (t - s) / 8.0);
        }
        return 0;
    }

    private void particles(Entity e, ParticleOptions type, int count) {
        for (int i = 0; i < count; i++) {
            mc.level.addParticle(type, e.getX() + (random.nextDouble() - 0.5) * 0.6, e.getY() + e.getBbHeight() + 0.2 + random.nextDouble() * 0.3,
                e.getZ() + (random.nextDouble() - 0.5) * 0.6, 0, 0.02, 0);
        }
    }

    private void tear(Entity e) {
        mc.level.addParticle(ParticleTypes.FALLING_WATER, e.getX() + (random.nextBoolean() ? 0.12 : -0.12), e.getEyeY(), e.getZ(), 0, 0, 0);
    }

    /** Its own voice, played just for you: pets are silent otherwise. */
    private void sound(Pet pet, float pitch) {
        if (!sounds.get() || !(pet.entity instanceof Mob mob)) return;
        SoundEvent sound = ((MobInvoker) mob).cyanite$ambientSound();
        if (sound == null) return;
        mc.level.playLocalSound(mob.getX(), mob.getY(), mob.getZ(), sound, SoundSource.NEUTRAL, 0.6f, pitch * (0.95f + random.nextFloat() * 0.1f), false);
    }

    private static void dance(Allay allay, boolean on) {
        allay.getEntityData().set(com.cyanite.client.mixin.AllayAccessor.cyanite$dancing(), on);
    }

    private static void interested(Entity e, boolean on) {
        if (e instanceof Wolf wolf) wolf.setIsInterested(on);
        else if (e instanceof Fox fox) fox.setIsInterested(on);
    }

    /** Sitting down, for the ones that can. */
    private static void rest(Entity e, boolean on) {
        if (e instanceof TamableAnimal tame) {
            if (tame.isInSittingPose() != on) tame.setInSittingPose(on);
        } else if (e instanceof Fox fox) {
            if (fox.isSitting() != on) fox.setSitting(on);
        } else if (e instanceof Panda panda) {
            if (panda.isSitting() != on) panda.sit(on);
        }
    }

    /** Gliding: held tucked under you, side by side, facing the way you go. */
    private void hold(Pet pet, int slot, LocalPlayer player) {
        Entity e = pet.entity;
        Vec3 look = player.getLookAngle();
        double flat = Math.hypot(look.x, look.z);
        Vec3 right = flat < 1.0E-3 ? new Vec3(1, 0, 0) : new Vec3(-look.z / flat, 0, look.x / flat);
        Vec3 spot = player.position()
            .add(0, player.getBbHeight() * 0.5 - 0.45 - e.getBbHeight() * 0.5, 0)
            .add(look.scale(0.55))
            .add(right.scale(HELD_SIDE[slot]));
        if (e instanceof LivingEntity living && living.getInterpolation() != null) living.getInterpolation().cancel();
        if (!pet.held) {
            pet.held = true;
            e.snapTo(spot.x, spot.y, spot.z, player.getYRot(), 0);
        } else {
            // Moved after its own tick, so it slides along with you between frames.
            e.setPos(spot);
            e.setYRot(player.getYRot());
        }
        if (e instanceof LivingEntity living) {
            living.setYBodyRot(player.getYRot());
            living.setYHeadRot(player.getYRot());
        }
        e.setOnGround(true);
        rest(e, true);
        pet.lostSince = 0;
    }

    private Pet spawn(String kind, String name, int slot, LocalPlayer player) {
        Entity entity = type(kind).create(mc.level, EntitySpawnReason.LOAD);
        if (entity == null) return null;
        entity.setId(FIRST_ID - slot);
        entity.setUUID(UUID.randomUUID());
        entity.setSilent(true);
        if (entity instanceof AgeableMob mob && baby.get()) mob.setBaby(true);
        // Tame: a wolf gets its collar and friendly face.
        if (entity instanceof TamableAnimal tame) tame.setTame(true, false);
        if (!name.isEmpty()) {
            entity.setCustomName(Component.literal(name));
            entity.setCustomNameVisible(true);
        }
        boolean flying = FLYING.contains(kind);
        Vec3 spot = landing(slot, player, flying);
        entity.snapTo(spot.x, spot.y, spot.z, player.getYRot(), 0);
        if (entity instanceof LivingEntity living) {
            living.setYHeadRot(player.getYRot());
            living.setYBodyRot(player.getYRot());
        }
        entity.setOnGround(!flying);
        mc.level.addEntity(entity);
        return new Pet(kind, name, baby.get(), entity);
    }

    private void remove(Pet pet) {
        if (mc.level != null && pet.entity.level() == mc.level && !pet.entity.isRemoved()) {
            mc.level.removeEntity(pet.entity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    /**
     * Keeps a pet on a ring round you, each at its own place on it: it stays put while it's there
     * and trots after you when you leave it behind, faster the further it has to go.
     */
    private void follow(Pet pet, int slot, LocalPlayer player, long now) {
        Entity entity = pet.entity;
        boolean flying = FLYING.contains(pet.kind);
        if (pet.held) {
            // Landed: put down, back on its feet.
            pet.held = false;
            rest(entity, false);
            pet.stillSince = now;
        }
        Vec3 at = entity.position();
        Vec3 you = player.position();
        double away = Math.hypot(at.x - you.x, at.z - you.z);

        // Out of sight round a corner or behind a wall for a moment: it comes round to you.
        boolean seen = mc.level.clip(new ClipContext(player.getEyePosition(), entity.getBoundingBox().getCenter(),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
        if (seen) pet.lostSince = 0;
        else if (pet.lostSince == 0) pet.lostSince = now;
        boolean lost = pet.lostSince != 0 && now - pet.lostSince > LOST_MS;
        if (away > CATCH_UP || Math.abs(at.y - you.y) > 8 || lost) {
            Vec3 spot = landing(slot, player, flying);
            entity.snapTo(spot.x, spot.y, spot.z, player.getYRot(), 0);
            if (entity instanceof LivingEntity living && living.getInterpolation() != null) living.getInterpolation().cancel();
            pet.lostSince = 0;
            pet.stillSince = now;
            return;
        }

        double keep = distance.get() + slot * 0.6;
        Vec3 next = at;
        boolean moving = false;
        if (away > keep + 0.4 || away < 0.9) {
            double angle = Math.atan2(at.z - you.z, at.x - you.x) + SPREAD[slot];
            double gx = you.x + Math.cos(angle) * keep;
            double gz = you.z + Math.sin(angle) * keep;
            double gap = Math.hypot(gx - at.x, gz - at.z);
            if (gap > 0.05) {
                double step = Math.min(gap, Mth.clamp(gap * 0.3, 0.07, 0.8));
                next = new Vec3(at.x + (gx - at.x) / gap * step, at.y, at.z + (gz - at.z) / gap * step);
                moving = true;
            }
        }
        double y;
        if (flying) {
            y = hover(entity, next.x, next.z, you.y) + 0.12 * Math.sin(now / 400.0 + slot * 2);
        } else {
            double floor = ground(next.x, next.z, you.y);
            y = Double.isNaN(floor) ? at.y : floor;
        }
        int t = moodTick(pet);
        Mood mood = t < 0 ? null : pet.mood;
        next = new Vec3(next.x, y + hop(mood, t), next.z);

        // The way it's going, or at you while it's still; at whatever it's reacting to, if anything.
        Vec3 eyes = pet.lookAt != null && pet.lookAt.isAlive() ? pet.lookAt.getEyePosition() : player.getEyePosition();
        float lookYaw = yawTo(next, eyes);
        float yaw = moving && mood == null ? yawTo(at, next) : lookYaw;
        // Excited: a spin on the spot.
        if (mood == Mood.EXCITED && t < 20) yaw = lookYaw + t * 18;
        double rise = eyes.y - (next.y + entity.getEyeHeight());
        float pitch = moving ? 0 : (float) Mth.clamp(-Math.toDegrees(Math.atan2(rise, Math.hypot(eyes.x - next.x, eyes.z - next.z))), -40, 40);
        if (entity instanceof LivingEntity living && living.getInterpolation() != null) living.getInterpolation().setInterpolationLength(2);
        entity.moveOrInterpolateTo(next, yaw, pitch);
        if (entity instanceof LivingEntity living) living.lerpHeadTo(mood == Mood.EXCITED && t < 20 || mood == null && moving ? yaw : lookYaw, 2);
        entity.setOnGround(!flying && hop(mood, t) < 0.05);

        if (moving || pet.stillSince == 0) pet.stillSince = now;
        // Sits when you stand still, and when you die; up on its feet for anything else.
        boolean resting = mood == Mood.SAD || mood == null && sit.get() && !moving && !flying && now - pet.stillSince > SIT_AFTER_MS
            && player.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4;
        rest(entity, resting);
    }

    /** Somewhere to put a pet down beside you: behind you, on floor you can see. At your feet if there's nowhere. */
    private Vec3 landing(int slot, LocalPlayer player, boolean flying) {
        Vec3 you = player.position();
        double keep = distance.get();
        for (double turn : new double[] {SPREAD[slot], SPREAD[slot] + 0.8, SPREAD[slot] - 0.8, 1.6, -1.6, Math.PI}) {
            double yaw = Math.toRadians(player.getYRot()) + turn;
            // Behind you: the opposite of where you look.
            double x = you.x + Math.sin(yaw) * keep;
            double z = you.z - Math.cos(yaw) * keep;
            double y = flying ? you.y + 2 : ground(x, z, you.y);
            if (Double.isNaN(y) || Math.abs(y - you.y) > 2.5) continue;
            Vec3 spot = new Vec3(x, y, z);
            if (mc.level.clip(new ClipContext(player.getEyePosition(), spot.add(0, 0.5, 0), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) continue;
            return spot;
        }
        return you;
    }

    /** How high a flying pet floats: just above your head, or as high as the ceiling lets it in a tunnel. */
    private double hover(Entity entity, double x, double z, double feet) {
        for (double up = 2.1; up > 0.3; up -= 0.2) {
            if (mc.level.noCollision(entity, entity.getDimensions(entity.getPose()).makeBoundingBox(x, feet + up, z))) return feet + up;
        }
        return feet + 0.3;
    }

    /** The top of the floor under (x, z) nearest {@code near}, with room above it to stand. NaN if there's none. */
    private double ground(double x, double z, double near) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        int base = Mth.floor(near);
        double best = Double.NaN;
        for (int y = base + 2; y >= base - 6; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
            if (shape.isEmpty()) continue;
            BlockPos above = pos.above();
            if (!mc.level.getBlockState(above).getCollisionShape(mc.level, above).isEmpty()) continue;
            double top = y + shape.max(Direction.Axis.Y);
            if (Double.isNaN(best) || Math.abs(top - near) < Math.abs(best - near)) best = top;
        }
        return best;
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90f;
    }

    private static EntityType<?> type(String kind) {
        return switch (kind) {
            case "Cat" -> EntityType.CAT;
            case "Fox" -> EntityType.FOX;
            case "Parrot" -> EntityType.PARROT;
            case "Allay" -> EntityType.ALLAY;
            case "Bee" -> EntityType.BEE;
            case "Axolotl" -> EntityType.AXOLOTL;
            case "Frog" -> EntityType.FROG;
            case "Rabbit" -> EntityType.RABBIT;
            case "Armadillo" -> EntityType.ARMADILLO;
            case "Panda" -> EntityType.PANDA;
            case "Chicken" -> EntityType.CHICKEN;
            default -> EntityType.WOLF;
        };
    }

    private static final class Pet {
        final String kind;
        final String name;
        final boolean baby;
        final Entity entity;
        long stillSince;
        long lostSince;
        boolean held;
        Mood mood;
        int moodStart;
        /** What it's reacting to, to look at; you, if null. */
        Entity lookAt;

        Pet(String kind, String name, boolean baby, Entity entity) {
            this.kind = kind;
            this.name = name;
            this.baby = baby;
            this.entity = entity;
        }
    }
}
