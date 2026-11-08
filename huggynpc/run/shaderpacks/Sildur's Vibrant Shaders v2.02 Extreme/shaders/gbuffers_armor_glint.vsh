#version 120
/* 
* ========================================================================
*   Sildur's Vibrant Shaders
*   https://sildurs-shaders.github.io/
*	https://modrinth.com/shader/sildurs-vibrant-shaders
*	https://www.curseforge.com/minecraft/shaders/sildurs-vibrant-shaders
* ========================================================================
*   Copyright (c) Sildur. All rights reserved.
*   https://x.com/SildurFX
*   Redistribution, modification, or mirroring without explicit 
*   written permission is strictly prohibited.
* ========================================================================
*/


#define AA_settings
#include "shaders.settings"

varying vec4 color;
varying vec2 texcoord;

uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;

#ifdef TAA
uniform float viewWidth;
uniform float viewHeight;
vec2 texelSize = vec2(1.0/viewWidth,1.0/viewHeight);
uniform int framemod8;
const vec2[8] offsets = vec2[8](vec2(1./8.,-3./8.),
								vec2(-1.,3.)/8.,
								vec2(5.0,1.)/8.,
								vec2(-3,-5.)/8.,
								vec2(-5.,5.)/8.,
								vec2(-7.,-1.)/8.,
								vec2(3,7.)/8.,
								vec2(7.,-7.)/8.);
#endif

void main() {
	
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;

    vec3 viewPos = (gl_ModelViewMatrix * gl_Vertex).xyz;
	vec3 position = mat3(gbufferModelViewInverse) * viewPos + gbufferModelViewInverse[3].xyz;

	color = gl_Color;

	gl_Position = gl_ProjectionMatrix * gbufferModelView * vec4(position, 1.0);
#ifdef TAA
	gl_Position.xy += offsets[framemod8] * gl_Position.w*texelSize;
#endif
}