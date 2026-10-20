#version 330

// One Quake III material stage. Vertex deforms (wave, normal, bulge, move), tcGen, tcMod, rgbGen
// and alphaGen are evaluated here from the static Q3Stage block and the per-view time, following
// the reference CPU evaluators of engine:render-ir (StageEvaluator, VertexDeformer).

#moj_import <craftq3:q3_material.glsl>

in vec3 Position;
in vec2 UV0;
in vec2 LightmapUV;
in vec3 Normal;
in vec4 Color;

out vec2 texCoord;
out vec2 lightmapCoord;
out vec4 stageColor;
out vec3 worldPosition;
out vec3 worldNormal;

float shaderSeconds() {
    float seconds = ViewOrigin.w - Ambient.w;
    if (M.x > 0.0) seconds = min(seconds, M.x);
    return seconds;
}

void deform(inout vec3 p, inout vec3 n, float seconds) {
    int count = int(D[0].x + 0.5);
    for (int i = 0; i < 4; i++) {
        if (i >= count) break;
        int at = 1 + i * 9;
        int op = int(df(at) + 0.5);
        if (op == 0) {
            float divisor = df(at + 6);
            float spread = divisor == 0.0 ? 100.0 : 1.0 / divisor;
            float amount = wave(df(at + 1), df(at + 2), df(at + 3), df(at + 4), df(at + 5), seconds,
                                (p.x + p.y + p.z) * spread);
            p += n * amount;
        } else if (op == 1) {
            vec3 s = p * 0.98;
            float t = seconds * df(at + 7);
            float amplitude = df(at + 6);
            n += vec3(noise4(vec4(s, t)), noise4(vec4(s.x + 100.0, s.yz, t)),
                      noise4(vec4(s.x + 200.0, s.yz, t))) * amplitude;
            float len = length(n);
            n = len > 1e-12 ? n / len : vec3(0.0);
        } else if (op == 2) {
            float amount = sin(UV0.x * df(at + 6) + seconds * df(at + 8)) * df(at + 7);
            p += n * amount;
        } else if (op == 3) {
            float amount = wave(df(at + 1), df(at + 2), df(at + 3), df(at + 4), df(at + 5), seconds, 0.0);
            p += vec3(df(at + 6), df(at + 7), df(at + 8)) * amount;
        }
    }
}

vec2 coordinates(vec3 local, vec3 world, vec3 n, float seconds) {
    int gen = int(sf(17) + 0.5);
    vec3 eye = ViewOrigin.xyz;
    vec2 st;
    if (gen == 0) st = UV0;
    else if (gen == 1) st = LightmapUV;
    else if (gen == 2) {
        vec3 view = normalize(eye - world);
        float d = 2.0 * dot(n, view);
        st = vec2(0.5 + 0.5 * (n.y * d - view.y), 0.5 - 0.5 * (n.z * d - view.z));
    } else if (gen == 3) {
        st = vec2(dot(local, vec3(sf(18), sf(19), sf(20))), dot(local, vec3(sf(21), sf(22), sf(23))));
    } else if (gen == 4) st = vec2(0.0);
    else st = vec2(length(world - eye) / 256.0, 0.5);
    int count = int(sf(24) + 0.5);
    for (int i = 0; i < 8; i++) {
        if (i >= count) break;
        int at = 28 + i * 12;
        int op = int(sf(at) + 0.5);
        if (op == 0) {
            float cycles = sf(at + 4) + seconds * sf(at + 5);
            float amplitude = sf(at + 3);
            st += vec2(sin(((local.x + local.z) / 1024.0 + cycles) * TAU),
                       sin((local.y / 1024.0 + cycles) * TAU)) * amplitude;
        } else if (op == 1) {
            st *= vec2(sf(at + 6), sf(at + 7));
        } else if (op == 2) {
            vec2 scroll = vec2(seconds * sf(at + 6), seconds * sf(at + 7));
            st += scroll - floor(scroll);
        } else if (op == 3) {
            float value = wave(sf(at + 1), sf(at + 2), sf(at + 3), sf(at + 4), sf(at + 5), seconds, 0.0);
            float inverse = 1.0 / (abs(value) < 1e-6 ? (value < 0.0 ? -1e-6 : 1e-6) : value);
            st = (st - 0.5) * inverse + 0.5;
        } else if (op == 4) {
            st = vec2(st.x * sf(at + 6) + st.y * sf(at + 8) + sf(at + 10),
                      st.x * sf(at + 7) + st.y * sf(at + 9) + sf(at + 11));
        } else if (op == 5) {
            float angle = radians(-sf(at + 6) * seconds);
            float s = sin(angle), c = cos(angle);
            vec2 centred = st - 0.5;
            st = vec2(centred.x * c - centred.y * s, centred.x * s + centred.y * c) + 0.5;
        } else if (op == 6) {
            st += TexOffset.xy;
        }
    }
    return st;
}

vec3 rgbGen(vec3 world, vec3 n, float seconds) {
    int op = int(sf(0) + 0.5);
    if (op == 0 || op == 1) return vec3(1.0);
    if (op == 2 || op == 3) return Color.rgb;
    if (op == 4) return vec3(1.0) - Color.rgb;
    if (op == 5) return EntityColor.rgb;
    if (op == 6) return vec3(1.0) - EntityColor.rgb;
    if (op == 7) {
        vec3 l = LightDir.xyz;
        float lambert = max(0.0, dot(normalize(n), length(l) > 0.0 ? normalize(l) : vec3(0.0)));
        return Ambient.rgb + Directed.rgb * lambert;
    }
    if (op == 8) return vec3(wave(sf(1), sf(2), sf(3), sf(4), sf(5), seconds, 0.0));
    if (op == 9) return vec3(sf(6), sf(7), sf(8));
    int volume = int(Directed.w + 0.5);
    return Directed.w >= 0.0 ? FogVolume[volume * 2].rgb : vec3(0.0);
}

float alphaGen(vec3 world, vec3 n, float seconds) {
    int op = int(sf(9) + 0.5);
    vec3 eye = ViewOrigin.xyz;
    if (op == 0) return 1.0;
    if (op == 1) return Color.a;
    if (op == 2) return 1.0 - Color.a;
    if (op == 3) return EntityColor.a;
    if (op == 4) return 1.0 - EntityColor.a;
    if (op == 5) {
        vec3 normal = normalize(n);
        vec3 toLight = length(LightDir.xyz) > 0.0 ? normalize(LightDir.xyz) : vec3(0.0);
        vec3 reflected = normal * 2.0 * dot(normal, toLight) - toLight;
        return pow(max(0.0, dot(reflected, normalize(eye - world))), 4.0);
    }
    if (op == 6) return wave(sf(10), sf(11), sf(12), sf(13), sf(14), seconds, 0.0);
    if (op == 7) return sf(15);
    return length(world - eye) / max(1e-6, sf(16));
}

void main() {
    float seconds = shaderSeconds();
    vec3 p = Position;
    vec3 n = Normal;
    deform(p, n, seconds);
    vec4 world = Model * vec4(p, 1.0);
    vec3 wn = mat3(Model) * n;
    float wl = length(wn);
    wn = wl > 1e-12 ? wn / wl : vec3(0.0, 0.0, 1.0);
    worldPosition = world.xyz;
    worldNormal = wn;
    texCoord = coordinates(p, world.xyz, wn, seconds);
    lightmapCoord = LightmapUV;
    stageColor = clamp(vec4(rgbGen(world.xyz, wn, seconds), alphaGen(world.xyz, wn, seconds)), 0.0, 1.0);
    vec4 clip = ViewProj * world;
    int flags = int(LightDir.w + 0.5);
    if ((flags & 1) != 0) clip.z = clip.w - (clip.w - clip.z) * 0.3;
    gl_Position = clip;
}
