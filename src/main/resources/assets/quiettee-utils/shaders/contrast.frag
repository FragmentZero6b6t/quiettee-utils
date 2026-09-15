#version 330 core

precision highp float;

in vec2 uv;
out vec4 color;

uniform sampler2D u_Texture;

layout (std140) uniform ContrastData {
    vec2 u_TexelSize;
    float u_Mode;
    float u_Threshold;
    float u_Levels;
    float u_Invert;
    float u_Edges;
    float u_EdgeThreshold;
};

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec3 c = texture(u_Texture, uv).rgb;
    float l = luma(c);
    int mode = int(u_Mode + 0.5);
    vec3 result;

    if (mode == 0) {
        result = vec3(l >= u_Threshold ? 1.0 : 0.0);
    } else if (mode == 1) {
        float levels = max(u_Levels, 2.0);
        result = vec3(min(floor(l * levels), levels - 1.0) / (levels - 1.0));
    } else {
        float levels = max(u_Levels, 2.0);
        result = min(floor(c * levels), vec3(levels - 1.0)) / (levels - 1.0);
    }

    if (u_Invert > 0.5) result = vec3(1.0) - result;

    if (u_Edges > 0.5) {
        float l1 = luma(texture(u_Texture, uv + vec2(u_TexelSize.x, 0.0)).rgb);
        float l2 = luma(texture(u_Texture, uv - vec2(u_TexelSize.x, 0.0)).rgb);
        float l3 = luma(texture(u_Texture, uv + vec2(0.0, u_TexelSize.y)).rgb);
        float l4 = luma(texture(u_Texture, uv - vec2(0.0, u_TexelSize.y)).rgb);
        float gradient = abs(l1 - l2) + abs(l3 - l4);

        if (gradient > u_EdgeThreshold) {
            result = luma(result) > 0.5 ? vec3(0.0) : vec3(1.0);
        }
    }

    color = vec4(result, 1.0);
}
