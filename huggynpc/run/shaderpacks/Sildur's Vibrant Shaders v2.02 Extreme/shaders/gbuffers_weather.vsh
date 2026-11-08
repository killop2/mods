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


#define gbuffers_weather
#include "shaders.settings"

varying vec4 color;
varying vec2 texcoord;

uniform vec3 cameraPosition;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferModelView;

void main() {

	vec3 position = mat3(gbufferModelViewInverse) * (gl_ModelViewMatrix * gl_Vertex).xyz + gbufferModelViewInverse[3].xyz;
#ifdef WeatherAngle
	float worldpos = position.y + cameraPosition.y;
	bool istopv = worldpos > cameraPosition.y+5.0;
	if (!istopv) position.xz += vec2(3.0,1.0);
#endif
	gl_Position = gl_ProjectionMatrix * gbufferModelView * vec4(position, 1.0);
	
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	color = gl_Color;
}
