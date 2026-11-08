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

varying vec2 texcoord;
varying vec4 color;
uniform sampler2D texture;

#ifdef Fog
varying float dist;
uniform float far;
uniform int isEyeInWater;
#if defined(IS_IRIS) || MC_VERSION >= 11802	//optifine added fog uniforms in 1.18.2
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
#endif
#ifdef DISTANT_HORIZONS
uniform int dhRenderDistance;
#endif
#ifdef VOXY
uniform int vxRenderDistance;
#endif
#endif

#ifdef DISTANT_HORIZONS
uniform float viewHeight;
uniform float viewWidth;
uniform sampler2D dhDepthTex1;
#endif

void main() {

#if Clouds == 1
	gl_FragData[0] = texture2D(texture, texcoord.xy)*color;

#ifdef Fog
    float newFar = far;
	#ifdef DISTANT_HORIZONS
    	newFar = max(far, float(dhRenderDistance * 16.0)) * 256.0;
	#endif 
	#ifdef VOXY
    	newFar = max(far, float(vxRenderDistance * 16.0));
	#endif
    #if defined(IS_IRIS) || MC_VERSION >= 11802	//optifine added fog uniforms in 1.18.2
    	gl_FragData[0].rgb = mix(gl_FragData[0].rgb * 0.85, fogColor, clamp(max((dist - fogStart) / max(fogEnd - fogStart, 0.0001), dist / newFar * 12.5 - 11.5), 0.0, 1.0));
	#else
    	gl_FragData[0].rgb = mix(gl_FragData[0].rgb * 0.85, gl_Fog.color.rgb, (isEyeInWater > 0.9) ? clamp(1.0 - exp(-dist * gl_Fog.density), 0.0, 1.0) : clamp(max((dist - gl_Fog.start) / max(gl_Fog.end - gl_Fog.start, 0.0001), dist / newFar * 12.5 - 11.5), 0.0, 1.0));
	#endif
#endif
#else
	gl_FragData[0] = vec4(0.0);
#endif
}

