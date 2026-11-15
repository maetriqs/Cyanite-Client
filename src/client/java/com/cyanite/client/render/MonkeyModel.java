package com.cyanite.client.render;

import com.cyanite.client.modules.misc.Pets;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Pets' monkey: brown fur, a tan heart-shaped face with a muzzle, round ears, a tan belly and a long
 * tail that curls at the tip. It trots on all fours, sits up when it rests, and stands on its hind
 * legs to act out a reaction: cheering, clapping, startled with its arms up, or hands over its eyes.
 * Sitting idle it scratches its head now and then; it blinks and flicks its ears.
 */
public class MonkeyModel extends EntityModel<MonkeyRenderer.State> {
    private static final float PI = (float) Math.PI;
    private static final String[] PARTS = {"body", "head", "left_arm", "right_arm", "left_leg", "right_leg", "tail0"};
    // Where each part sits (x, y, z) and how it's turned (x, y, z), in PARTS order. Pixels, y down, front is -z.
    private static final float[][] STAND = {
        {0, 16.5f, 0.5f, -0.35f, 0, 0}, {0, 13, -3.5f, 0, 0, 0},
        {3, 15, -3, 0, 0, 0}, {-3, 15, -3, 0, 0, 0},
        {1.5f, 18, 3, 0, 0, 0}, {-1.5f, 18, 3, 0, 0, 0},
        {0, 15.5f, 4.5f, 0.25f, 0, 0},
    };
    private static final float[][] SIT = {
        {0, 20, 2, -1.45f, 0, 0}, {0, 16, 0, 0, 0, 0},
        {3, 17, -0.5f, -0.6f, 0, 0}, {-3, 17, -0.5f, -0.6f, 0, 0},
        {1.5f, 22.5f, 1, -1.4f, -0.2f, 0}, {-1.5f, 22.5f, 1, -1.4f, 0.2f, 0},
        {0, 23, 4.5f, -0.1f, 0, 0},
    };
    /** On its hind legs, arms free. */
    private static final float[][] UPRIGHT = {
        {0, 15, 1.5f, -1.4f, 0, 0}, {0, 11.5f, 0.5f, 0, 0, 0},
        {3, 12, 0.5f, 0, 0, 0.1f}, {-3, 12, 0.5f, 0, 0, -0.1f},
        {1.5f, 18, 1.5f, 0, 0, 0}, {-1.5f, 18, 1.5f, 0, 0, 0},
        {0, 18.5f, 4.5f, -0.2f, 0, 0},
    };
    private static final int[] TAIL_LENGTH = {4, 4, 3, 3, 2, 2};
    /** How much each tail piece bends up from the one before: gently at the root, curled at the tip. */
    private static final float[] TAIL_BEND = {0, 0.55f, 0.6f, 0.95f, 1.15f, 1.25f};

    private final ModelPart[] parts = new ModelPart[PARTS.length];
    private final ModelPart body;
    private final ModelPart head;
    private final ModelPart leftEar;
    private final ModelPart rightEar;
    private final ModelPart lids;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;
    private final ModelPart[] tail = new ModelPart[TAIL_LENGTH.length];

    public MonkeyModel(ModelPart root) {
        super(root);
        for (int i = 0; i < PARTS.length; i++) parts[i] = root.getChild(PARTS[i]);
        body = parts[0];
        head = parts[1];
        leftArm = parts[2];
        rightArm = parts[3];
        leftLeg = parts[4];
        rightLeg = parts[5];
        leftEar = head.getChild("left_ear");
        rightEar = head.getChild("right_ear");
        lids = head.getChild("lids");
        tail[0] = parts[6];
        for (int i = 1; i < tail.length; i++) tail[i] = tail[i - 1].getChild("tail" + i);
    }

    /** Laid out on textures/entity/monkey.png (64x64). */
    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 13).addBox(-2.5f, -3, -4, 5, 6, 8), pose(STAND[0]));
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4, -6, -5, 8, 7, 6), pose(STAND[1]));
        head.addOrReplaceChild("muzzle", CubeListBuilder.create().texOffs(28, 0).addBox(-2, -2, -1.5f, 4, 3, 2), PartPose.offset(0, 0, -5));
        head.addOrReplaceChild("left_ear", CubeListBuilder.create().texOffs(40, 0).addBox(0, -1.5f, -0.5f, 3, 3, 1),
            PartPose.offsetAndRotation(4, -3.5f, -2, 0, -0.3f, 0));
        head.addOrReplaceChild("right_ear", CubeListBuilder.create().texOffs(48, 0).addBox(-3, -1.5f, -0.5f, 3, 3, 1),
            PartPose.offsetAndRotation(-4, -3.5f, -2, 0, 0.3f, 0));
        // Eyelids, just in front of the eyes, shown for a blink.
        head.addOrReplaceChild("lids", CubeListBuilder.create().texOffs(56, 0).addBox(-2, -4, -5.1f, 1, 2, 1)
            .texOffs(56, 0).addBox(1, -4, -5.1f, 1, 2, 1), PartPose.ZERO);
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(26, 13).addBox(-1, 0, -1, 2, 9, 2), pose(STAND[2]));
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(34, 13).addBox(-1, 0, -1, 2, 9, 2), pose(STAND[3]));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(42, 13).addBox(-1, 0, -1, 2, 6, 2), pose(STAND[4]));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(50, 13).addBox(-1, 0, -1, 2, 6, 2), pose(STAND[5]));
        // The tail: a chain of pieces, each hung off the end of the one before.
        int[] texU = {0, 10, 20, 28, 36, 42};
        PartDefinition piece = root.addOrReplaceChild("tail0",
            CubeListBuilder.create().texOffs(texU[0], 27).addBox(-0.5f, -0.5f, 0, 1, 1, TAIL_LENGTH[0]), pose(STAND[6]));
        for (int i = 1; i < TAIL_LENGTH.length; i++) {
            piece = piece.addOrReplaceChild("tail" + i,
                CubeListBuilder.create().texOffs(texU[i], 27).addBox(-0.5f, -0.5f, 0, 1, 1, TAIL_LENGTH[i]),
                PartPose.offsetAndRotation(0, 0, TAIL_LENGTH[i - 1] - 0.3f, TAIL_BEND[i], 0, 0));
        }
        return LayerDefinition.create(mesh, 64, 64);
    }

    private static PartPose pose(float[] p) {
        return PartPose.offsetAndRotation(p[0], p[1], p[2], p[3], p[4], p[5]);
    }

    @Override
    public void setupAnim(MonkeyRenderer.State state) {
        super.setupAnim(state);
        float sit = state.sit;
        float up = state.upright;
        for (int i = 0; i < parts.length; i++) {
            float[] a = STAND[i];
            float[] b = UPRIGHT[i];
            float[] c = SIT[i];
            float[] p = new float[6];
            for (int k = 0; k < 6; k++) p[k] = Mth.lerp(sit, Mth.lerp(up, a[k], b[k]), c[k]);
            ModelPart part = parts[i];
            part.x = p[0];
            part.y = p[1];
            part.z = p[2];
            part.xRot = p[3];
            part.yRot = p[4];
            part.zRot = p[5];
        }
        float age = state.ageInTicks + state.phase;

        // Trotting: diagonal legs together on all fours, just the legs when upright, with a bob.
        float gait = state.walkAnimationPos * 0.6662f;
        float speed = state.walkAnimationSpeed;
        float onFours = (1 - sit) * (1 - up);
        float onLegs = 1 - sit;
        rightLeg.xRot += Mth.cos(gait) * 1.2f * speed * onLegs;
        leftLeg.xRot += Mth.cos(gait + PI) * 1.2f * speed * onLegs;
        rightArm.xRot += Mth.cos(gait + PI) * 0.9f * speed * onFours;
        leftArm.xRot += Mth.cos(gait) * 0.9f * speed * onFours;
        float bob = Math.abs(Mth.sin(gait)) * 0.8f * speed * onLegs;
        for (ModelPart part : new ModelPart[] {body, head, leftArm, rightArm, tail[0]}) part.y -= bob;

        head.yRot = state.yRot * Mth.DEG_TO_RAD;
        // Only part of the look up at you: on a neck this short the full pitch shows its chin, not its face.
        head.xRot = state.xRot * Mth.DEG_TO_RAD * 0.55f + Mth.sin(gait * 2) * 0.06f * speed * onFours;
        head.zRot = state.headRoll;
        if (state.isBaby) {
            // A baby's head is big for its body.
            head.xScale = 1.25f;
            head.yScale = 1.25f;
            head.zScale = 1.25f;
        }

        // A blink every few seconds, and now and then an ear flicks.
        lids.visible = Mth.positiveModulo(age, 70f) < 2.5f;
        leftEar.yRot -= flick(age, 0);
        rightEar.yRot += flick(age, 55);

        // The tail sways side to side, a ripple runs down it, and it trails out behind at a run.
        tail[0].yRot += Mth.sin(age * 0.08f) * 0.35f + Mth.cos(gait) * 0.5f * speed * onLegs;
        for (int i = 1; i < tail.length; i++) {
            tail[i].xRot = TAIL_BEND[i] * (1 - 0.3f * speed * onLegs) + Mth.sin(age * 0.12f - i * 0.7f) * 0.08f;
            tail[i].zRot = Mth.sin(age * 0.1f - i * 0.8f) * 0.1f;
        }

        arms(state, age, sit);
    }

    /** How far an ear is turned back: a quick flick every five seconds, offset per ear. */
    private static float flick(float age, float offset) {
        float t = Mth.positiveModulo(age + offset, 100f);
        return t < 6 ? Mth.sin(t / 6 * PI) * 0.5f : 0;
    }

    /** Reactions are acted out with the arms; sitting idle, a head scratch now and then. */
    private void arms(MonkeyRenderer.State state, float age, float sit) {
        float blend = state.moodBlend;
        if (state.mood != null && blend > 0) {
            float wave = Mth.sin(age * 0.9f) * 0.35f;
            switch (state.mood) {
                // Both arms up, waving.
                case EXCITED -> {
                    pose(leftArm, blend, -2.9f + wave, 0, 0.4f);
                    pose(rightArm, blend, -2.9f - wave, 0, -0.4f);
                }
                // Clapping in front of its chest.
                case HAPPY -> {
                    float clap = 0.25f + Mth.sin(age * 1.4f) * 0.2f;
                    pose(leftArm, blend, -1.4f, clap, 0);
                    pose(rightArm, blend, -1.4f, -clap, 0);
                }
                // Arms flung up and out, trembling.
                case ALARMED -> {
                    float shake = Mth.sin(age * 2.5f) * 0.06f;
                    pose(leftArm, blend, -2.6f + shake, 0, 0.7f);
                    pose(rightArm, blend, -2.6f - shake, 0, -0.7f);
                }
                // Hands over its eyes.
                case WORRIED, SAD -> {
                    pose(leftArm, blend, -2.3f, 0.3f, 0);
                    pose(rightArm, blend, -2.3f, -0.3f, 0);
                }
            }
            return;
        }
        float t = Mth.positiveModulo(age, 220f);
        if (t < 50 && sit > 0.5f) {
            float k = Mth.clamp(Math.min(t, 50 - t) / 8f, 0, 1) * sit;
            pose(rightArm, k, -2.9f, 0, -0.3f + Mth.sin(age * 1.3f) * 0.12f);
        }
    }

    private static void pose(ModelPart arm, float blend, float xRot, float yRot, float zRot) {
        arm.xRot = Mth.lerp(blend, arm.xRot, xRot);
        arm.yRot = Mth.lerp(blend, arm.yRot, yRot);
        arm.zRot = Mth.lerp(blend, arm.zRot, zRot);
    }

    /** Whether a reaction gets the monkey up on its hind legs. */
    public static boolean standsFor(Pets.Mood mood) {
        return mood != null && mood != Pets.Mood.SAD;
    }
}
