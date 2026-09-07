#version 330
uniform sampler2D StageTexture;
uniform sampler2D SkyMask;
layout(std140) uniform Q3Stage {
    ivec4 AlphaTest;
};
layout(std140) uniform Q3Camera {
    mat4 ViewProjection;
    vec4 ClipPlane;
    vec4 Viewport;
};
in vec3 worldPosition;
in vec2 texCoord;
in vec4 vertexColor;
in float fogAmount;
out vec4 fragColor;
void main() {
    if (dot(vec4(worldPosition, 1.0), ClipPlane) < -0.01) discard;
    if (AlphaTest.w == 1 && texture(SkyMask, gl_FragCoord.xy / Viewport.xy).r < 0.5) discard;
    vec2 uv = AlphaTest.y == 1 ? gl_FragCoord.xy / Viewport.xy : texCoord;
    vec4 color = texture(StageTexture, uv) * vertexColor;
    if (AlphaTest.x == 1 && color.a <= 0.0) discard;
    if (AlphaTest.x == 2 && color.a >= 0.5) discard;
    if (AlphaTest.x == 3 && color.a < 0.5) discard;
    float transmission = 1.0 - clamp(fogAmount, 0.0, 1.0);
    if (AlphaTest.z == 1) color.rgb *= transmission;
    if (AlphaTest.z == 2) color.a *= transmission;
    if (AlphaTest.z == 3) color *= transmission;
    if (AlphaTest.z == 4) color.rgb = mix(vec3(1.0), color.rgb, transmission);
    fragColor = color;
}
