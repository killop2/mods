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


#define Fog_settings
#include "shaders.settings"

#ifdef defskybox
#if MC_VERSION < 11700
varying vec4 color;
varying float dist;
#else
varying vec4 glColor;
#endif
#endif

void main() {
	gl_Position = ftransform();
#ifdef defskybox
#if MC_VERSION < 11700
	gl_FogFragCoord = gl_Position.z;
	color = gl_Color;
	dist = length(gl_ModelViewMatrix * gl_Vertex);
#else
	glColor = vec4(gl_Color.rgb, float(gl_Color.r == gl_Color.g && gl_Color.g == gl_Color.b && gl_Color.r > 0.0)); 	//rgb = star color, a = flag for weather or not this pixel is a star.
#endif
#endif
}