// Shared declarations of the q3_material program. Block layouts are written by Q3Blocks (Java).

// Per view: the combined view-projection (viewport baked in), eye and refdef time, the portal
// clip plane, target metrics and the scene's dynamic lights.
layout(std140) uniform Q3View {
    mat4 ViewProj;
    vec4 ViewOrigin;   // xyz eye, w refdef seconds
    vec4 ClipPlane;    // xyz normal, w distance; kept where dot(n, p) - d >= 0
    vec4 ViewInfo;     // x 1/target width, y 1/target height, z clip plane enabled, w unused
    vec4 LightInfo;    // x light count
    vec4 Lights[64];   // per light: (origin.xyz, radius), (colour.rgb, 0)
};

// Per draw: entity transform, colour and lighting.
layout(std140) uniform Q3Draw {
    mat4 Model;
    vec4 EntityColor;  // rgba 0..1
    vec4 Ambient;      // rgb ambient light, w seconds subtracted from the view time
    vec4 Directed;     // rgb directed light, w fog volume index (-1 none)
    vec4 LightDir;     // xyz toward the light, w flags (1 depth hack)
    vec4 TexOffset;    // xy entity texture offset (tcMod entityTranslate)
};

// Per material stage (static): ProgramLayout's stage block in S, the material's deforms in D.
// S[31]: x alpha test, y fog mode, z pass flags, w unused. M: x clamp time.
layout(std140) uniform Q3Stage {
    vec4 S[32];
    vec4 D[10];
    vec4 M;
};

// Per map (static): convex fog volumes as packed planes.
layout(std140) uniform Q3Fog {
    vec4 FogHeader;       // x volume count
    vec4 FogVolume[32];   // per volume: (colour.rgb, depthForOpaque), (firstPlane, planeCount, 0, 0)
    vec4 FogPlane[128];   // (unit normal, distance); inside where dot(n, p) - d <= 0
};

const float TAU = 6.28318530717958647692;
const int FLAG_LIGHTMAP = 1;
const int FLAG_SCREEN = 2;
const int FLAG_FOG_PASS = 4;
const int FLAG_DLIGHT = 8;
const int MAX_FOG_VOLUMES = 16;

float sf(int i) {
    return S[i >> 2][i & 3];
}

float df(int i) {
    return D[i >> 2][i & 3];
}

float lattice(int x, int y, int z, int t) {
    uint h = (uint(x) * 0x8da6b343u) ^ (uint(y) * 0xd8163841u) ^ (uint(z) * 0xcb1ab31fu)
        ^ (uint(t) * 0x165667b1u);
    h ^= h >> 16u;
    h *= 0x85ebca6bu;
    h ^= h >> 13u;
    h *= 0xc2b2ae35u;
    h ^= h >> 16u;
    return float(h >> 8u) * (1.0 / 8388608.0) - 1.0;
}

float smoothWeight(float t) {
    return t * t * (3.0 - 2.0 * t);
}

float noise4(vec4 p) {
    vec4 cell = floor(p);
    ivec4 i = ivec4(cell);
    vec4 f = vec4(smoothWeight(p.x - cell.x), smoothWeight(p.y - cell.y),
                  smoothWeight(p.z - cell.z), smoothWeight(p.w - cell.w));
    float result = 0.0;
    for (int corner = 0; corner < 16; corner++) {
        int ox = corner & 1, oy = (corner >> 1) & 1, oz = (corner >> 2) & 1, ot = corner >> 3;
        float w = (ox == 0 ? 1.0 - f.x : f.x) * (oy == 0 ? 1.0 - f.y : f.y)
            * (oz == 0 ? 1.0 - f.z : f.z) * (ot == 0 ? 1.0 - f.w : f.w);
        result += w * lattice(i.x + ox, i.y + oy, i.z + oz, i.w + ot);
    }
    return result;
}

// Waveforms.evaluate: base + amplitude * f(phase + offset + seconds * frequency).
float wave(float func, float base, float amp, float phase, float freq, float seconds, float offset) {
    float cycles = phase + offset + seconds * freq;
    float t = cycles - floor(cycles);
    int fn = int(func + 0.5);
    float v;
    if (fn == 0) v = sin(t * TAU);
    else if (fn == 1) v = t < 0.25 ? 4.0 * t : (t < 0.75 ? 2.0 - 4.0 * t : 4.0 * t - 4.0);
    else if (fn == 2) v = t < 0.5 ? 1.0 : -1.0;
    else if (fn == 3) v = t;
    else if (fn == 4) v = 1.0 - t;
    else v = noise4(vec4(0.0, 0.0, 0.0, cycles));
    return base + amp * v;
}

// FogVolumes.sample: composites every volume the eye-to-point segment crosses, farthest first.
vec4 fogAt(vec3 point) {
    int count = min(int(FogHeader.x + 0.5), MAX_FOG_VOLUMES);
    if (count == 0) return vec4(0.0);
    vec3 eye = ViewOrigin.xyz;
    vec3 d = point - eye;
    float len = length(d);
    if (len < 1e-4) return vec4(0.0);
    float begins[MAX_FOG_VOLUMES];
    float ends[MAX_FOG_VOLUMES];
    bool hits[MAX_FOG_VOLUMES];
    for (int v = 0; v < MAX_FOG_VOLUMES; v++) {
        hits[v] = false;
        begins[v] = 0.0;
        ends[v] = 0.0;
        if (v >= count) continue;
        vec4 info = FogVolume[v * 2 + 1];
        int first = int(info.x + 0.5), planes = int(info.y + 0.5);
        float begin = 0.0, end = 1.0;
        bool hit = true;
        for (int i = 0; i < planes; i++) {
            vec4 plane = FogPlane[first + i];
            float distance = dot(eye, plane.xyz) - plane.w;
            float direction = dot(d, plane.xyz);
            if (abs(direction) < 1e-6) {
                if (distance > 1e-3) { hit = false; break; }
                continue;
            }
            float crossing = -distance / direction;
            if (direction > 0.0) end = min(end, crossing);
            else begin = max(begin, crossing);
            if (begin >= end) { hit = false; break; }
        }
        hits[v] = hit && end > begin;
        begins[v] = begin;
        ends[v] = end;
    }
    vec3 rgb = vec3(0.0);
    float opacity = 0.0;
    float last = 1e30;
    for (int pass = 0; pass < MAX_FOG_VOLUMES; pass++) {
        if (pass >= count) break;
        int pick = -1;
        for (int v = 0; v < MAX_FOG_VOLUMES; v++) {
            if (v >= count) break;
            if (hits[v] && begins[v] <= last && (pick < 0 || begins[v] > begins[pick])) pick = v;
        }
        if (pick < 0) break;
        hits[pick] = false;
        last = begins[pick];
        vec4 volume = FogVolume[pick * 2];
        float alpha = sqrt(clamp((ends[pick] - begins[pick]) * len / volume.w, 0.0, 1.0));
        rgb = volume.rgb * alpha + rgb * (1.0 - alpha);
        opacity = alpha + opacity * (1.0 - alpha);
    }
    if (opacity <= 0.0) return vec4(0.0);
    return vec4(rgb / opacity, clamp(opacity, 0.0, 1.0));
}
