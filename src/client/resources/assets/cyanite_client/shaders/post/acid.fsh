#version 330

// Cyanite's Acid Mode: the world's picture in rainbow bands that drift across it, cycling through
// every hue, with a slow wobble, colours split towards the edges and turned-up saturation.
uniform sampler2D SceneSampler;

layout(std140) uniform AcidConfig {
    vec4 Clock; // colour clock (turns of the hue wheel), wobble clock in seconds, strength (0 plain to 1 full), unused
    vec4 Look;  // rainbow, wobble, colour split, extra saturation
};

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

/** A pure colour off the hue wheel, 0 to 1 once round. */
vec3 rainbow(float hue) {
    return clamp(abs(fract(hue + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

/** Turns the colour round the hue wheel by that many turns, keeping its brightness. */
vec3 hueShift(vec3 color, float turns) {
    float a = turns * 6.2831853;
    vec3 grey = vec3(0.57735027);
    float c = cos(a);
    return color * c + cross(grey, color) * sin(a) + grey * dot(grey, color) * (1.0 - c);
}

vec3 scene(vec2 uv) {
    return texture(SceneSampler, clamp(uv, vec2(0.0005), vec2(0.9995))).rgb;
}

void main() {
    float hueClock = Clock.x;
    float t = Clock.y;
    float strength = Clock.z;
    vec2 centred = texCoord - 0.5;

    // Wobble: two slow waves each way, so the picture breathes rather than shakes.
    vec2 wobble = vec2(
        sin(texCoord.y * 9.0 + t * 1.7) + 0.5 * sin(texCoord.y * 23.0 - t * 2.9),
        cos(texCoord.x * 8.0 - t * 1.3) + 0.5 * cos(texCoord.x * 19.0 + t * 2.3)) * 0.004 * Look.y * strength;
    vec2 uv = texCoord + wobble;

    // Colour split, wider towards the edges.
    vec2 split = centred * 0.014 * Look.z * strength;
    vec3 color = vec3(scene(uv + split).r, scene(uv).g, scene(uv - split).b);

    // Rainbow bands drifting diagonally, rippling out from the middle.
    float band = hueClock + (texCoord.x + texCoord.y) * 0.55 + 0.12 * sin(length(centred) * 11.0 - t * 2.4);
    float luma = dot(color, LUMA);
    vec3 shifted = hueShift(color, band);
    // Greys have no hue to turn: they take the band's colour at their own brightness.
    vec3 painted = rainbow(band) * luma * 1.9 + shifted * 0.25;
    vec3 acid = mix(shifted, painted, 0.55);
    color = mix(color, acid, Look.x * strength);

    float after = dot(color, LUMA);
    color = mix(vec3(after), color, 1.0 + Look.w * strength);

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
