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


#define gbuffers_shadows
#include "shaders.settings"

#if defined Shadows || defined Volumetric_Lighting
varying vec4 color;
varying vec4 texcoord;
attribute vec4 mc_Entity;

vec2 utilShadowDistortion(vec2 shadowpos) {
  float distortion = log(length(shadowpos.xy)*b+a)*k;
  return shadowpos.xy / distortion;
}
#endif

void main() {

vec4 position = gl_ModelViewProjectionMatrix * gl_Vertex;

#if defined Shadows || defined Volumetric_Lighting
	position.xy = utilShadowDistortion(position.xy);
	position.z /= 6.0;

	texcoord.xy = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	texcoord.z = 0.0;
	texcoord.w = 0.0;
	if(mc_Entity.x == 10008.0) texcoord.z = 1.0;
	#ifndef grass_shadows
	if(mc_Entity.x == 10031.0 || mc_Entity.x == 10059.0 || mc_Entity.x == 10175.0 || mc_Entity.x == 10176.0) texcoord.w = 1.0;
	#endif

	color = gl_Color;
#endif	
	gl_Position = position;
}
