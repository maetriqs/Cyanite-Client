#version 330

// Cyanite's space sky (Cinematic): a panorama looked up by view direction, turned to the Sky color,
// with a haze at the horizon in the fog's color so the far terrain blends into it, and stars drawn
// here rather than taken from the picture, so they stay sharp at any zoom.
uniform sampler2D SkySampler;

layout(std140) uniform SpaceSkyConfig {
    mat4 ScreenToWorld; // screen position to a camera-relative world point
    mat4 SkyTurn;       // world direction to picture direction (the sky turns with the stars)
    mat4 SkyToScreen;   // picture direction back to the screen, to measure the stars in pixels
    mat4 Tint;          // the picture's colors to the Sky color, times Sky brightness
    vec4 Look;          // contrast curve power, curve gain, rain dimming, how much of it shows
    vec4 Haze;          // fog color, haze at the horizon
    vec4 Stars;         // density (0 for none), flicker (0 or 1), seconds, star size in pixels
    vec4 Screen;        // width and height in pixels
};

in vec2 texCoord;

out vec4 fragColor;

const float PI = 3.14159265;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

uvec4 pcg4(uvec4 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.w; v.y += v.z * v.x; v.z += v.x * v.y; v.w += v.y * v.z;
    v ^= v >> 16u;
    v.x += v.y * v.w; v.y += v.z * v.x; v.z += v.x * v.y; v.w += v.y * v.z;
    return v;
}

vec4 random4(ivec2 cell, int face, int salt) {
    return vec4(pcg4(uvec4(uvec2(cell + 4096), uint(face), uint(salt)))) * (1.0 / 4294967296.0);
}

// A point on a cube face (even angles, -1 to 1) as a direction.
vec3 faceDirection(vec2 uv, int face) {
    uv = tan(uv * (PI / 4.0));
    if (face == 0) return vec3(1.0, uv.y, uv.x);
    if (face == 1) return vec3(-1.0, uv.y, uv.x);
    if (face == 2) return vec3(uv.x, 1.0, uv.y);
    if (face == 3) return vec3(uv.x, -1.0, uv.y);
    if (face == 4) return vec3(uv.x, uv.y, 1.0);
    return vec3(uv.x, uv.y, -1.0);
}

// One layer of stars: a grid over each cube face, at most one star per cell. Each star is put on
// the screen and drawn as a round dot about a pixel wide, so it's sharp and the same anywhere on
// screen at any zoom. The brightest get a faint halo.
vec3 starLayer(vec2 uv, int face, int layer, float cells, float chance, float dim, float bright, float halo) {
    vec2 g = (uv * 0.5 + 0.5) * cells;
    ivec2 corner = ivec2(floor(g - 0.5));
    vec3 light = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        ivec2 cell = corner + ivec2(i & 1, i >> 1);
        if (cell.x < 0 || cell.y < 0 || cell.x >= int(cells) || cell.y >= int(cells)) continue;
        vec4 r = random4(cell, face, layer);
        if (r.x >= chance) continue;
        vec4 q = random4(cell, face, layer + 8);
        // Cells on a face's edge keep their star away from it, so the next face can't cut it.
        vec2 lo = mix(vec2(0.1), vec2(0.35), vec2(equal(cell, ivec2(0))) + vec2(equal(cell, ivec2(int(cells) - 1))));
        vec2 at = vec2(cell) + mix(lo, 1.0 - lo, r.yz);
        vec4 clip = SkyToScreen * vec4(faceDirection(at / cells * 2.0 - 1.0, face), 0.0);
        if (clip.w <= 0.0) continue;
        vec2 d = (clip.xy / clip.w * 0.5 + 0.5) * Screen.xy - gl_FragCoord.xy;
        float b = mix(dim, bright, r.w * r.w);
        float size = Stars.w * (0.55 + 0.25 * b + 0.15 * q.x);
        float d2 = dot(d, d);
        float glow = exp(-d2 / (2.0 * size * size)) + halo * exp(-d2 / (2.0 * 7.0 * size * size));
        if (glow < 0.003) continue;
        // Now and then one flickers for a moment.
        if (Stars.y > 0.5 && q.y < 0.3) {
            float t = Stars.z;
            float moment = pow(0.5 + 0.5 * sin(t * (0.2 + 0.45 * q.z) + 6.2832 * q.w), 16.0);
            b *= 1.0 - 0.9 * moment * (0.5 + 0.5 * sin(t * (17.0 + 9.0 * q.z) + 40.0 * q.w));
        }
        vec3 tint = q.z < 0.14 ? vec3(1.0, 0.8, 0.62) : q.z > 0.78 ? vec3(0.74, 0.85, 1.0) : vec3(1.0);
        light += tint * b * glow;
    }
    return light;
}

vec3 stars(vec3 d) {
    vec3 a = abs(d);
    vec2 uv;
    int face;
    if (a.x >= a.y && a.x >= a.z) { uv = d.zy / a.x; face = d.x > 0.0 ? 0 : 1; }
    else if (a.y >= a.z) { uv = d.xz / a.y; face = d.y > 0.0 ? 2 : 3; }
    else { uv = d.xy / a.z; face = d.z > 0.0 ? 4 : 5; }
    // Even angles across the face, so the corners aren't crowded.
    uv = atan(uv) * (4.0 / PI);
    float density = Stars.x;
    // About 5000 faint, 2000 middling and 500 bright over the whole sky at density 1.
    return starLayer(uv, face, 1, 120.0, min(0.95, 0.06 * density), 0.2, 0.5, 0.0)
         + starLayer(uv, face, 2, 64.0, min(0.95, 0.085 * density), 0.45, 0.95, 0.0)
         + starLayer(uv, face, 3, 30.0, min(0.95, 0.1 * density), 0.9, 1.8, 0.06);
}

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 near = ScreenToWorld * vec4(ndc, -1.0, 1.0);
    vec4 far = ScreenToWorld * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(far.xyz / far.w - near.xyz / near.w);
    vec3 sky = normalize((SkyTurn * vec4(dir, 0.0)).xyz);

    vec2 at = vec2(atan(sky.x, -sky.z) / (2.0 * PI) + 0.5, acos(clamp(sky.y, -1.0, 1.0)) / PI);
    vec3 color = texture(SkySampler, at).rgb;
    // Deep space: the dark parts go darker than the picture, its glow stays.
    float luma = max(dot(color, LUMA), 1e-4);
    color *= Look.y * pow(luma, Look.x - 1.0);
    color = max((Tint * vec4(color, 0.0)).rgb, 0.0) * Look.z;

    // The haze at the horizon (stronger below it, like the game's own sky), in the fog's color.
    float up = dir.y;
    float haze = up >= 0.0 ? Haze.a * exp(-up / 0.09) : mix(Haze.a, 1.0, smoothstep(0.0, 0.35, -up));
    if (Stars.x > 0.0) {
        // Stars are a pixel wide anywhere, so how many land on a pixel follows how much sky it
        // covers: a wide FOV crowds them in the middle and thins them at the edges. Weighting
        // their light by that (against the middle of a FOV 70 view) keeps the sky even, and
        // zooming in brings out a few more, like a telescope.
        float pixelSky = length(cross(dFdx(dir), dFdy(dir)));
        float usual = 1.4004 / Screen.y; // one pixel's angle in the middle at FOV 70
        float weight = clamp(sqrt(usual * usual / max(pixelSky, 1e-12)), 0.4, 1.6);
        color += stars(sky) * weight * Look.z * Look.z * (1.0 - haze);
    }
    color = mix(color, Haze.rgb, haze);

    // A little noise so slow gradients don't band.
    float noise = random4(ivec2(gl_FragCoord.xy), 7, int(Stars.z * 60.0) & 1023).x - 0.5;
    fragColor = vec4(color + noise / 255.0, Look.w);
}
