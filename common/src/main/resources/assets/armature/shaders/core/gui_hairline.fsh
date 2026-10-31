#version 330
#extension GL_ARB_separate_shader_objects : require

// See gui_hairline.vsh. A fill draws its colour, as the game's gui.fsh does. A segment keeps only the pixels
// of Hairlines' walk: one pixel per column (or row) along its longer axis, the other axis rounded half up.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 local;
layout(location = 2) flat in vec2 delta;

layout(location = 0) out vec4 fragColor;

// n / c rounded half up, for c > 0: floor((2n + c) / (2c)), with the division floored for a negative top.
int roundedQuotient(int n, int c) {
    int top = 2 * n + c;
    int bottom = 2 * c;
    return top >= 0 ? top / bottom : -((bottom - 1 - top) / bottom);
}

bool onSegment(ivec2 cell, ivec2 d) {
    int ax = abs(d.x);
    int ay = abs(d.y);
    if (ax >= ay) {
        int i = d.x > 0 ? cell.x : -cell.x;
        return i >= 0 && i <= ax && cell.y == roundedQuotient(d.y * i, ax);
    }
    int i = d.y > 0 ? cell.y : -cell.y;
    return i >= 0 && i <= ay && cell.x == roundedQuotient(d.x * i, ay);
}

void main() {
    ivec2 d = ivec2(delta);
    if ((d.x != 0 || d.y != 0) && !onSegment(ivec2(floor(local)), d)) {
        discard;
    }
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
