#version 330
#extension GL_ARB_separate_shader_objects : require

// Armature's GUI fills and one-pixel lines, in one pipeline so they keep their painter's order.
// The uniform blocks are copied from the game's gui.vsh rather than included, as the game's own GUI shaders do.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
// Where this corner is from the segment's start, in GUI pixels. Unused by a fill.
layout(location = 2) in vec2 UV0;
// The segment's run, dx and dy. Zero for a fill.
layout(location = 3) in ivec2 UV2;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 local;
layout(location = 2) flat out vec2 delta;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    local = UV0;
    delta = vec2(UV2);
}
