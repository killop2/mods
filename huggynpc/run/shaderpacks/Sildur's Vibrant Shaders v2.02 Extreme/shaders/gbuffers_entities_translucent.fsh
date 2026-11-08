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


#define gbuffers_shadows
#define AA_settings
#define Fog_settings
#include "shaders.settings"

varying vec2 texcoord;
varying vec4 color;

varying vec3 LightC;
varying vec3 ambientC;
varying vec3 shadowData;
varying vec3 vertexShadowPos;
varying vec3 normal;

varying float skyL;
varying float SkyL2;
varying float dist;

uniform sampler2D texture;
uniform sampler2D colortex4;
uniform sampler2DShadow shadowtex0;
//uniform sampler2DShadow shadowtex1; //colored shadows in gbuffer are not that fast
//uniform sampler2D shadowcolor0;

uniform vec3 shadowLightPosition;
uniform mat4 gbufferProjectionInverse;
uniform int isEyeInWater;

uniform float far;
uniform float viewWidth;
uniform float viewHeight;
uniform float frameTimeCounter;
uniform ivec2 eyeBrightnessSmooth;

vec3 utilScreenSpace(vec3 pos) {
	vec4 iProjDiag = vec4(gbufferProjectionInverse[0].x, gbufferProjectionInverse[1].y, gbufferProjectionInverse[2].zw);
	vec3 p3 = pos * 2.0 - 1.0;
    vec4 fragposition = iProjDiag * p3.xyzz + gbufferProjectionInverse[3];
    return fragposition.xyz / fragposition.w;
}

#ifdef Fog
uniform float blindness;
#if defined(IS_IRIS) || MC_VERSION >= 11802	//optifine added fog uniforms in 1.18.2
	uniform vec3 fogColor;
	uniform float fogStart;
	uniform float fogEnd;
#endif
#if MC_VERSION >= 11900
	uniform float darknessFactor;
	uniform float darknessLightFactor; 
#else
	#define darknessFactor 0.0
	#define darknessLightFactor 0.0
#endif
#ifdef DISTANT_HORIZONS
	uniform int dhRenderDistance;
#endif
#ifdef VOXY
	uniform int vxRenderDistance;
#endif

vec3 funFog(vec3 albedo, vec2 newTC, vec3 fragpos) {
	#if defined(IS_IRIS) || MC_VERSION >= 11802	
		vec3 fogFallback = fogColor;
	#else
		vec3 fogFallback = gl_Fog.color.rgb;
	#endif

	//sample color from deferred, blend in caves.
	vec3 fogC = mix(fogFallback * 0.1, pow(texture2D(colortex4, newTC).rgb, vec3(2.2)), mix(skyL,1.0,clamp((eyeBrightnessSmooth.y/255.0-0.125)*4.0,0.0,1.0)));

	#ifdef defskybox
		fogC = pow(fogFallback * 0.7, vec3(3.2));
	#endif

	float newFar = far;
	
	#ifdef DISTANT_HORIZONS
   		newFar = max(far, float(dhRenderDistance * 16.0)) * 256.0;
	#endif
	#ifdef VOXY
    	newFar = max(far, float(vxRenderDistance * 16.0));
	#endif
	
	float fogScaling = (isEyeInWater == 1.0) ? (uFogDensity - 1.0) * 11.5 : (wFogDensity - 1.0) * 11.5;
	float fogDistance = dist / newFar * 12.5 - 11.5 + fogScaling;

	float toggleFog = 1.0;
	#ifndef Underwater_Fog
    	if (isEyeInWater == 1.0) toggleFog = 0.0;
	#endif

	//blend vanilla fog, underwater, lava, snow, use if statement for fog scaling.
	if(isEyeInWater == 1.0) fogC = fogFallback * 2.0 * fogC.b;
	if(isEyeInWater == 2.0) fogC = fogFallback * 0.2;
	if(isEyeInWater == 3.0) fogC = fogFallback * 0.01;

  	#if defined(IS_IRIS) || MC_VERSION >= 11802
    	if (darknessFactor <= 0.01 && blindness <= 0.01) albedo = mix(albedo, fogC, clamp(max((dist - fogStart) / max(fogEnd - fogStart, 0.0001), fogDistance), 0.0, 1.0) * toggleFog);
	#else
    	albedo = mix(albedo, fogC, (isEyeInWater > 0.9) ? clamp(1.0 - exp(-dist * gl_Fog.density), 0.0, 1.0) * toggleFog : clamp(max((dist - gl_Fog.start) / max(gl_Fog.end - gl_Fog.start, 0.0001), fogDistance), 0.0, 1.0)) * toggleFog;
	#endif

	return albedo;
}
#endif

#ifdef TAA
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

#ifdef Shadows
vec3 funShadows(float noise, float skyL, vec3 fragpos, vec3 normal) {
	float diffuse = shadowData.r;
	vec3 finalShading = vec3(diffuse);
	if (fragpos.z > -0.38) fragpos.z -= 0.38; 

	if (diffuse > 0.001) {
	vec2 shading = vec2(1.0);
	if (abs(vertexShadowPos.x) < 1.0-1.5/shadowMapResolution && abs(vertexShadowPos.y) < 1.0-1.5/shadowMapResolution && abs(vertexShadowPos.z) < 6.0){  //only if on shadowmap
		float rdMul = shadowData.g;
		float bias = shadowData.b;
	#ifdef TAA
		float wSHsamples = 2.0;	//hardcode low sample rate
	#else
		float wSHsamples = 6.0;
	#endif
		float rShadowSamples = 1.0 / wSHsamples;
		vec2 shadows = vec2(0.0);
		for(int i = 0; i < wSHsamples; i++){
			float alpha = (float(i) + noise) * rShadowSamples;
			float angle = (noise + alpha * 4.0) * 6.2831853;
			vec2 offsetS = vec2(cos(angle), sin(angle)) * sqrt(alpha);

			float weight = 1.0+(i+noise)*rdMul*rShadowSamples*shadowMapResolution;
			
			shadows.x += shadow2D(shadowtex0,vec3(vertexShadowPos + vec3(rdMul*offsetS,-bias*weight))).x;
		//#ifdef ColoredShadows
			//shadows.y += shadow2D(shadowtex1,vec3(vertexShadowPos + vec3(rdMul*offsetS,-bias*weight))).x;
		//#endif
		}
		shading = shadows * rShadowSamples;
	}
	#if defined raytracedShadows || defined VOXY
		//if(shading.x > 0.005)shading.xy *= funRaytraceShadows(shadowLightPosition, fragpos.xyz, noise, float(translucent));
	#endif
	//#ifdef ColoredShadows
		//finalShading = texture2D(shadowcolor0, vertexShadowPos.xy).rgb*(shading.y-shading.x) + shading.x;
		//finalShading *= diffuse;
	//#else
		finalShading = vec3(shading.x)*diffuse;
	//#endif
		//Prevent light leakage
		finalShading *= mix(skyL,1.0,clamp((eyeBrightnessSmooth.y/255.0-0.125)*4.0,0.0,1.0));
	}
	return finalShading;
}
#endif

void main() {

	vec2 newTC = gl_FragCoord.xy / vec2(viewWidth, viewHeight);	
	vec4 albedo = texture2D(texture, texcoord.xy) * color;
		 albedo.rgb = pow(albedo.rgb, vec3(2.2));

	#ifdef TAA
		vec3 fragpos = utilScreenSpace(vec3(gl_FragCoord.xy*texelSize-offsets[framemod8]*texelSize*0.5, vec3(gl_FragCoord.z, gl_FragCoord.xy*texelSize).x));
		float noise = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y + frameTimeCounter * 16.0);
	#else
		vec3 fragpos = utilScreenSpace(vec3(newTC, gl_FragCoord.z));
		float noise = fract(gl_FragCoord.x * 0.618033988749895 + gl_FragCoord.y * 0.24412852441);
	#endif

	//sky
	if(gl_FragCoord.z >= 1.0) {

	//land
	} else {
	#ifdef Shadows
		float entityBoost = 4.0;
		albedo.rgb *= (funShadows(noise, skyL, fragpos, normal.xyz)*LightC*(SkyL2*skyL)*2.15+ambientC*(1.0/(SkyL2*skyL*0.5+0.5))*1.4)*0.63 * entityBoost;
	#else
		float entityBoost = 4.0;
		float dif = clamp(dot(normal.xyz, normalize(shadowLightPosition)),0.0,1.0);
			  dif *= mix(skyL,1.0,clamp((eyeBrightnessSmooth.y/255.0-0.25)*4.0,0.0,1.0));
			  dif *= 0.5;
		vec3 finalLight = (dif*LightC*(SkyL2*skyL)*2.15+ambientC*(1.0/(SkyL2*skyL*0.5+0.5))*1.4)*0.63 * entityBoost;
		albedo.rgb *= finalLight;
	#endif

	#ifdef Fog
		albedo.rgb = funFog(albedo.rgb, newTC, fragpos);
	#endif
	}

	//albedo.rgb = (albedo.rgb * pow(eyeAdapt,0.88));
	albedo.rgb = pow(albedo.rgb, vec3(0.454));

	gl_FragData[0] = albedo;
}