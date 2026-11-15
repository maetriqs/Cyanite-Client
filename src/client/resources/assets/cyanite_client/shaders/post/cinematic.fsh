#version 330

// Cyanite's Cinematic picture: background blur by depth, then brightness, contrast, saturation,
// warmth, the rain's mood and the vignette.
uniform sampler2D SceneSampler;
uniform sampler2D BlurSampler;
// The world's depth, kept before the game cleared it for the hand.
uniform sampler2D DepthSampler;
// The depth after the hand was drawn: below 1 only where the hand is, which stays sharp.
uniform sampler2D HandDepthSampler;

layout(std140) uniform CinematicConfig {
    vec4 Grade;   // saturation, contrast, brightness, temperature (-1 cool to 1 warm)
    vec4 Effects; // vignette, background blur, focus distance in blocks, unused
    vec4 View;    // near plane, far plane, mood (rain), unused
};

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float blocksAway(float depth) {
    float z = depth * 2.0 - 1.0;
    float near = View.x;
    float far = View.y;
    return 2.0 * near * far / (far + near - z * (far - near));
}

vec3 warm(vec3 color, float amount) {
    vec3 tinted = color * vec3(1.0 + 0.2 * amount, 1.0 + 0.04 * amount, 1.0 - 0.2 * amount);
    // Keep the brightness where it was: warmth only shifts the hue.
    return tinted * (dot(color, LUMA) / max(dot(tinted, LUMA), 0.0001));
}

void main() {
    vec3 color = texture(SceneSampler, texCoord).rgb;

    if (Effects.y > 0.0 && texture(HandDepthSampler, texCoord).r >= 1.0) {
        float depth = texture(DepthSampler, texCoord).r;
        float distance = depth >= 1.0 ? View.y * 4.0 : blocksAway(depth);
        float focus = Effects.z;
        // Sharp up to the focus, softening over the next stretch behind it (longer the farther you look).
        float behind = smoothstep(0.0, 1.0, (distance - focus) / (focus * 0.6 + 4.0));
        color = mix(color, texture(BlurSampler, texCoord).rgb, behind * Effects.y);
    }

    float mood = View.z;
    color *= Grade.z * (1.0 - 0.12 * mood);
    color = (color - 0.5) * Grade.y + 0.5;
    color = max(color, 0.0);
    float luma = dot(color, LUMA);
    color = mix(vec3(luma), color, Grade.x * (1.0 - 0.3 * mood));
    color = warm(color, clamp(Grade.w - 0.35 * mood, -1.0, 1.0));

    if (Effects.x > 0.0) {
        float edge = length(texCoord - 0.5) * 1.4142;
        color *= 1.0 - Effects.x * 0.85 * smoothstep(0.3, 1.05, edge);
    }

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
