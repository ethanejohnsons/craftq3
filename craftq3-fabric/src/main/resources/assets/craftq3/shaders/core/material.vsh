#version 330
in vec3 Position;
in vec2 UV0;
in vec4 Color;
in float Fog;
layout(std140) uniform Q3Camera {
    mat4 ViewProjection;
    vec4 ClipPlane;
    vec4 Viewport;
};
out vec2 texCoord;
out vec3 worldPosition;
out vec4 vertexColor;
out float fogAmount;
void main() {
    gl_Position = ViewProjection * vec4(Position, 1.0);
    texCoord = UV0;
    worldPosition = Position;
    vertexColor = Color;
    fogAmount = Fog;
}
