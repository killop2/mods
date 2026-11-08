#version 120
/* DRAWBUFFERS:4 */
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
uniform int isEyeInWater;
uniform float far;
uniform float fogStart;
uniform float fogEnd;
uniform vec3 fogColor;
#ifdef DISTANT_HORIZONS
uniform int dhRenderDistance;
#endif
#ifdef VOXY
uniform int vxRenderDistance;
#endif
#else
uniform float viewHeight;
uniform float viewWidth;
uniform mat4 gbufferModelView;
uniform mat4 gbufferProjectionInverse;
uniform vec3 fogColor;
uniform vec3 skyColor;
varying vec4 glColor;

vec3 funSkyColor(vec3 pos) {
	float up = max(dot(pos, gbufferModelView[1].xyz), 0.0);
	return mix(skyColor, fogColor, 0.025 / (up * up + 0.025));
}
#endif
#endif

void main() {
#ifdef defskybox
#if MC_VERSION < 11700
	gl_FragData[0] = color;
    float newFar = far;
	#ifdef DISTANT_HORIZONS
    	newFar = max(far, float(dhRenderDistance * 16.0)) * 256.0;
	#endif 
	#ifdef VOXY
    	newFar = max(far, float(vxRenderDistance * 16.0));
	#endif
    #if defined(IS_IRIS) || MC_VERSION >= 11802	//optifine added fog uniforms in 1.18.2
    	gl_FragData[0].rgb = mix(gl_FragData[0].rgb, fogColor, clamp(max((dist - fogStart) / max(fogEnd - fogStart, 0.0001), dist / newFar * 12.5 - 11.5), 0.0, 1.0));
	#else
    	gl_FragData[0].rgb = mix(gl_FragData[0].rgb, gl_Fog.color.rgb, (isEyeInWater > 0.9) ? clamp(1.0 - exp(-dist * gl_Fog.density), 0.0, 1.0) : clamp(max((dist - gl_Fog.start) / max(gl_Fog.end - gl_Fog.start, 0.0001), dist / newFar * 12.5 - 11.5), 0.0, 1.0));
	#endif
#else
	vec4 pos = gbufferProjectionInverse * vec4(gl_FragCoord.xy / vec2(viewWidth, viewHeight) * 2.0 - 1.0, 1.0, 1.0);
	vec3 skyColor = funSkyColor(normalize(pos.xyz));
	if (glColor.a > 0.5) skyColor = glColor.rgb;
	
	gl_FragData[0] = vec4(skyColor, 1.0);
#endif	
#else
	gl_FragData[0] = vec4(0.0);
#endif
}