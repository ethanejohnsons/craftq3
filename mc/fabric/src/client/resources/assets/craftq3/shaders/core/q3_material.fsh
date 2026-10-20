#version 330

#moj_import <craftq3:q3_material.glsl>

uniform sampler2D Diffuse;
uniform sampler2D Lightmap;

in vec2 texCoord;
in vec2 lightmapCoord;
in vec4 stageColor;
in vec3 worldPosition;
in vec3 worldNormal;

out vec4 fragColor;

vec3 dynamicLights(vec3 p, vec3 n) {
    int count = min(int(LightInfo.x + 0.5), 32);
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 32; i++) {
        if (i >= count) break;
        vec4 light = Lights[i * 2];
        vec3 toLight = light.xyz - p;
        float distance = length(toLight);
        if (distance >= light.w) continue;
        float lambert = distance < 1e-4 ? 1.0 : max(0.0, dot(toLight, n) / distance);
        float ratio = distance / light.w;
        sum += Lights[i * 2 + 1].rgb * lambert * (1.0 - ratio * ratio);
    }
    return sum;
}

void main() {
    if (ViewInfo.z > 0.5 && dot(worldPosition, ClipPlane.xyz) - ClipPlane.w < 0.0) discard;
    int flags = int(S[31].z + 0.5);
    if ((flags & FLAG_FOG_PASS) != 0) {
        fragColor = fogAt(worldPosition);
        return;
    }
    if ((flags & FLAG_DLIGHT) != 0) {
        fragColor = vec4(dynamicLights(worldPosition, normalize(worldNormal)), 1.0);
        return;
    }
    vec2 uv = (flags & FLAG_SCREEN) != 0 ? gl_FragCoord.xy * ViewInfo.xy : texCoord;
    vec4 color = texture(Diffuse, uv) * stageColor;
    if ((flags & FLAG_LIGHTMAP) != 0) color.rgb *= texture(Lightmap, lightmapCoord).rgb;
    int alphaTest = int(S[31].x + 0.5);
    if (alphaTest == 1 && color.a <= 0.0) discard;
    if (alphaTest == 2 && color.a >= 0.5) discard;
    if (alphaTest == 3 && color.a < 0.5) discard;
    int fogMode = int(S[31].y + 0.5);
    if (fogMode != 0 && Directed.w >= 0.0) {
        float transmission = 1.0 - fogAt(worldPosition).a;
        if (fogMode == 1) color.rgb *= transmission;
        else if (fogMode == 2) color.a *= transmission;
        else if (fogMode == 3) color *= transmission;
        else color.rgb = mix(vec3(1.0), color.rgb, transmission);
    }
    fragColor = color;
}
