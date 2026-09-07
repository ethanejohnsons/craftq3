#version 330
in vec3 Position;
in vec4 Color;
layout(std140) uniform Q3Camera {
    mat4 ViewProjection;
};
out vec4 vertexColor;
void main() {
    gl_Position = ViewProjection * vec4(Position, 1.0);
    vertexColor = Color;
}
