#version 330

uniform sampler2D Diffuse;
uniform sampler2D Lightmap;

layout(std140) uniform Q3Stage {
    vec4 Modes;
    vec4 RgbWave;
    vec4 ConstColor;
    vec4 Options;    // x lightmap, y alphaFunc (0 none, 1 GT0, 2 LT128, 3 GE128)
    vec4 TcMods[8];
};

in vec2 texCoord;
in vec2 lightmapCoord;
in vec4 stageColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Diffuse, texCoord) * stageColor;
    if (Options.x > 0.5) color.rgb *= texture(Lightmap, lightmapCoord).rgb;
    int alphaFunc = int(Options.y + 0.5);
    if (alphaFunc == 1 && color.a <= 0.0) discard;
    if (alphaFunc == 2 && color.a >= 0.5) discard;
    if (alphaFunc == 3 && color.a < 0.5) discard;
    fragColor = color;
}
