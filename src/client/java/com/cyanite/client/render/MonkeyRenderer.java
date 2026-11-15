package com.cyanite.client.render;

import com.cyanite.client.modules.misc.Pets;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws Pets' monkey. Minecraft has no monkey, so the pet is a tame wolf underneath (it sits, tilts
 * its head and follows like the wolf does) and this draws it as a monkey instead; the swap is in
 * EntityRenderDispatcherMixin.
 */
public class MonkeyRenderer extends MobRenderer<Wolf, MonkeyRenderer.State, MonkeyModel> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("cyanite_client", "textures/entity/monkey.png");
    private static final float BABY = 0.55f;

    /** Each monkey's eased poses, so it sits down and gets up rather than snapping. */
    private final Map<Entity, Ease> eases = new WeakHashMap<>();

    public MonkeyRenderer(EntityRendererProvider.Context context) {
        super(context, new MonkeyModel(MonkeyModel.createLayer().bakeRoot()), 0.4f);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public Identifier getTextureLocation(State state) {
        return TEXTURE;
    }

    @Override
    public void extractRenderState(Wolf wolf, State state, float partialTick) {
        super.extractRenderState(wolf, state, partialTick);
        state.headRoll = wolf.getHeadRollAngle(partialTick);
        state.phase = Math.floorMod(wolf.getId(), 7) * 31f;

        Ease ease = eases.computeIfAbsent(wolf, e -> new Ease());
        // Called more than once a frame at times (shadow passes): ease by game time, not by calls.
        float dt = Mth.clamp(state.ageInTicks - ease.age, 0, 10);
        ease.age = state.ageInTicks;
        Pets.Mood mood = Pets.showing(wolf);
        ease.sit = approach(ease.sit, wolf.isInSittingPose() ? 1 : 0, dt * 0.12f);
        ease.upright = approach(ease.upright, MonkeyModel.standsFor(mood) ? 1 : 0, dt * 0.15f);
        // A new reaction waits for the arms to come down from the last one.
        if (mood != ease.mood) {
            ease.blend = approach(ease.blend, 0, dt * 0.25f);
            if (ease.blend == 0) ease.mood = mood;
        } else {
            ease.blend = approach(ease.blend, mood != null ? 1 : 0, dt * 0.25f);
        }
        state.sit = smooth(ease.sit);
        state.upright = smooth(ease.upright);
        state.mood = ease.mood;
        state.moodBlend = smooth(ease.blend);
    }

    @Override
    protected void scale(State state, PoseStack poseStack) {
        if (state.isBaby) poseStack.scale(BABY, BABY, BABY);
    }

    // Its tail and head reach past the wolf's box; don't cull it while some of it is still on screen.
    @Override
    protected AABB getBoundingBoxForCulling(Wolf wolf) {
        return super.getBoundingBoxForCulling(wolf).inflate(0.5);
    }

    private static float approach(float value, float target, float step) {
        return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
    }

    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    private static final class Ease {
        float age;
        float sit;
        float upright;
        float blend;
        Pets.Mood mood;
    }

    public static final class State extends LivingEntityRenderState {
        /** 0 on all fours, 1 sat down. */
        public float sit;
        /** Up on its hind legs, for a reaction. */
        public float upright;
        /** The wolf's head tilt (worried, or sad). */
        public float headRoll;
        /** Sets each monkey's blinks, flicks and sways apart from the others'. */
        public float phase;
        /** The reaction its arms show, and how far into that pose they are. */
        public Pets.Mood mood;
        public float moodBlend;
    }
}
