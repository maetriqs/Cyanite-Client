#version 330

// Cyanite's Cinematic background blur: one direction of a 9-tap gaussian (5 reads, using the
// linear filter to land between texels). Run across, then down.
uniform sampler2D InSampler;

layout(std140) uniform BlurConfig {
    vec4 Step; // xy: one step, in texture coordinates
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 step = Step.xy;
    vec3 sum = texture(InSampler, texCoord).rgb * 0.2270270270;
    sum += (texture(InSampler, texCoord + step * 1.3846153846).rgb + texture(InSampler, texCoord - step * 1.3846153846).rgb) * 0.3162162162;
    sum += (texture(InSampler, texCoord + step * 3.2307692308).rgb + texture(InSampler, texCoord - step * 3.2307692308).rgb) * 0.0702702703;
    fragColor = vec4(sum, 1.0);
}
