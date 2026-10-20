#version 330

// One Quake III shader stage, evaluated on the GPU. Everything that animates (rgbGen wave, tcMod,
// tcGen environment) is a function of the per-draw Q3Stage block and the per-view time, so static
// vertex buffers never need rewriting. Layout: Q3StageParams / Q3ViewParams.

in vec3 Position;
in vec2 UV0;
in vec2 LightmapUV;
in vec3 Normal;
in vec4 Color;

layout(std140) uniform Q3View {
    mat4 ViewProjection;
    vec4 ViewOrigin; // xyz eye, w time in seconds
};

layout(std140) uniform Q3Stage {
    vec4 Modes;      // x rgbGen, y wave func, z tcGen, w tcMod count
    vec4 RgbWave;    // base, amp, phase, freq
    vec4 ConstColor; // rgb const, a alphaGen const
    vec4 Options;    // x lightmap, y alphaFunc, z alphaGen
    vec4 TcMods[8];  // two vec4 per tcMod
};

out vec2 texCoord;
out vec2 lightmapCoord;
out vec4 stageColor;

const float TWO_PI = 6.28318530718;

float wave(int func, float base, float amp, float phase, float freq) {
    float x = phase + ViewOrigin.w * freq;
    float v;
    if (func == 0) v = sin(x * TWO_PI);
    else if (func == 1) v = sin(x * TWO_PI) >= 0.0 ? 1.0 : -1.0;
    else if (func == 2) v = 1.0 - 4.0 * abs(fract(x + 0.25) - 0.5);
    else if (func == 3) v = fract(x);
    else v = 1.0 - fract(x);
    return base + amp * v;
}

vec2 applyTcMod(vec2 st, vec4 a, vec4 b) {
    int type = int(a.x + 0.5);
    float t = ViewOrigin.w;
    if (type == 1) {
        // scroll: only the fractional offset matters and keeps precision bounded
        return st + fract(a.yz * t);
    } else if (type == 2) {
        float radians = -a.y * t * TWO_PI / 360.0;
        float s = sin(radians);
        float c = cos(radians);
        return vec2(st.x * c - st.y * s + (0.5 - 0.5 * c + 0.5 * s),
                    st.x * s + st.y * c + (0.5 - 0.5 * s - 0.5 * c));
    } else if (type == 3) {
        float now = a.w + t * b.x;
        return st + vec2(sin(((Position.x + Position.z) * (1.0 / 128.0) * 0.125 + now) * TWO_PI),
                         sin((Position.y * (1.0 / 128.0) * 0.125 + now) * TWO_PI)) * a.z;
    } else if (type == 4) {
        return st * a.yz;
    } else if (type == 5) {
        float p = 1.0 / wave(int(a.y + 0.5), a.z, a.w, b.x, b.y);
        return (st - 0.5) * p + 0.5;
    }
    return st;
}

void main() {
    gl_Position = ViewProjection * vec4(Position, 1.0);

    int tcGen = int(Modes.z + 0.5);
    vec2 st = UV0;
    if (tcGen == 1) {
        st = LightmapUV;
    } else if (tcGen == 2) {
        vec3 viewer = normalize(ViewOrigin.xyz - Position);
        float d = dot(Normal, viewer);
        vec3 reflected = Normal * 2.0 * d - viewer;
        st = vec2(0.5 + reflected.y * 0.5, 0.5 - reflected.z * 0.5);
    }
    int count = int(Modes.w + 0.5);
    for (int i = 0; i < 4; i++) {
        if (i >= count) break;
        st = applyTcMod(st, TcMods[i * 2], TcMods[i * 2 + 1]);
    }
    texCoord = st;
    lightmapCoord = LightmapUV;

    int rgbGen = int(Modes.x + 0.5);
    vec3 rgb;
    if (rgbGen == 0) rgb = vec3(1.0);
    else if (rgbGen == 1) rgb = Color.rgb;
    else if (rgbGen == 2) rgb = ConstColor.rgb;
    else rgb = vec3(clamp(wave(int(Modes.y + 0.5), RgbWave.x, RgbWave.y, RgbWave.z, RgbWave.w), 0.0, 1.0));

    int alphaGen = int(Options.z + 0.5);
    float alpha = alphaGen == 0 ? 1.0 : (alphaGen == 1 ? Color.a : ConstColor.a);
    stageColor = vec4(rgb, alpha);
}
