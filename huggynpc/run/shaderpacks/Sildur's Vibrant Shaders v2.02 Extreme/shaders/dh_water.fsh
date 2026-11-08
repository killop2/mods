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


#define gbuffers_water
#define AA_settings
#define Reflection_settings
#define Fog_settings
#include "shaders.settings"

varying vec2 texcoord;
varying vec4 color;

varying vec3 LightC;
varying vec3 ambientC;
varying vec3 viewVector;
varying vec3 worldpos;
varying mat3 tbnMatrix;
varying vec4 normal;

varying float skyL;
varying float SkyL2;
varying float dist;

uniform sampler2D texture;
uniform sampler2D colortex4;
uniform sampler2D colortex6;
uniform sampler2D depthtex1;
uniform sampler2D dhDepthTex1;
uniform sampler2D dhDepthTex0;
uniform sampler2D noisetex;

uniform vec3 cameraPosition;
uniform vec3 shadowLightPosition;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;

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
#if defined(IS_IRIS) || MC_VERSION >= 11802
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

vec3 funFog(vec3 albedo, vec2 newTC, vec3 fragpos) {
	#if defined(IS_IRIS) || MC_VERSION >= 11802	
		vec3 fogFallback = fogColor;
	#else
		vec3 fogFallback = gl_Fog.color.rgb;
	#endif

	vec3 fogC = pow(texture2D(colortex4, newTC).rgb, vec3(2.2));
	#ifdef defskybox
		fogC = pow(fogFallback * 0.7, vec3(3.2));
	#endif

	float newFar = far;
	#ifdef DISTANT_HORIZONS
   		newFar = max(far, float(dhRenderDistance * 16.0)) * 256.0;
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

#ifdef Reflections
uniform float dhNearPlane;
uniform float dhFarPlane;
uniform mat4 dhProjection;
varying vec3 vViewPos;

vec2 utilVectorToSky(vec3 dir) {
    float u = atan(dir.x, dir.z) / 6.28318530718 + 0.5;	
    float v = asin(dir.y) / 3.14159265359 + 0.5;		
    return vec2(u, v);									
}

vec4 funReflections(vec3 dir, float noise, float fresnel, vec3 skyC) {
    float steps = 12.0;
    float maxDist = ((vViewPos.z + dir.z * dhFarPlane * 1.73205) > -dhNearPlane) ? (-dhNearPlane - vViewPos.z) / dir.z : dhFarPlane * 1.73205;
   
    vec3 projDiag = vec3(dhProjection[0].x, dhProjection[1].y, dhProjection[2].z);
    vec3 clipPos = (projDiag * vViewPos + dhProjection[3].xyz) / -vViewPos.z * 0.5 + 0.5;
    vec3 targetPos = vViewPos + dir * maxDist;
    vec3 clipTarget = (projDiag * targetPos + dhProjection[3].xyz) / -targetPos.z * 0.5 + 0.5;
    vec3 direction = vec3(normalize((clipTarget - clipPos).xy), (clipTarget - clipPos).z / max(length((clipTarget - clipPos).xy), 0.0001));
    vec3 maxLengths = (step(0.0, direction) - clipPos) / direction;
    vec3 refstep = direction * min(min(maxLengths.x, maxLengths.y), maxLengths.z) / steps;
    vec3 refpos = clipPos + refstep * (noise); 
    
    #ifdef TAA
		refpos.xy += offsets[framemod8] * texelSize * 0.5;
    #endif

	vec2 minMax = vec2(0.0, refpos.z + refstep.z);
    vec4 reflC = vec4(skyC, 1.0);
    
    for (int i = 0; i <= int(steps); i++) {
        float depth = texture2D(dhDepthTex1, refpos.xy).x;
        if (depth > 0.56 && depth <= max(minMax.y, minMax.x) && depth >= min(minMax.y, minMax.x)) {
            if (refpos.x > 0.0 && refpos.y > 0.0 && refpos.x < 1.0 && refpos.y < 1.0) {
				reflC.a = 1.0;
                reflC.rgb = pow(texture2D(colortex4, refpos.xy).rgb, vec3(2.2));
                break; 
            }
        }
        refpos += refstep;
        float linearDepth = (2.0 * dhNearPlane) / (dhFarPlane + dhNearPlane - refpos.z * (dhFarPlane - dhNearPlane));
        minMax.x = minMax.y - 0.00004 / linearDepth;
        minMax.y += refstep.z;
    }
    return reflC;
}
#endif

mat2 rmatrix(float rad){
	return mat2(vec2(cos(rad), -sin(rad)), vec2(sin(rad), cos(rad)));
}

float funWaterWaves(vec2 coord, bool iswater){
	if(iswater){
	vec2 movement = abs(vec2(0.0, -frameTimeCounter * 0.31365));

	coord *= 0.262144;
	vec2 coord0 = coord * rmatrix(1.0) - movement * 4.0;
		 coord0.y *= 3.0;
	vec2 coord1 = coord * rmatrix(0.5) - movement * 1.5;
		 coord1.y *= 3.0;		 
	vec2 coord2 = coord + movement * 0.5;
		 coord2.y *= 3.0;
	
	coord0 *= waveSize;
	coord1 *= waveSize;

	float wave = 1.0 - texture2D(noisetex,coord0 * 0.005).x * 10.0;			//big waves
		  wave += texture2D(noisetex,coord1 * 0.010416).x * 7.0;			//small waves
		  wave += sqrt(texture2D(noisetex,coord2 * 0.045).x * 6.5) * 1.33;	//noise texture
		  wave *= 0.0157;
	return wave;
	} else return sqrt(texture2D(noisetex,coord * 0.5).x) * 0.015;			//translucent noise, non water, was 0.035
}

vec3 funWaterBump(vec2 coord, bool iswater){
	const vec2 deltaPos = vec2(0.25, 0.0);

	float h0 = funWaterWaves(coord, iswater);
	float h1 = funWaterWaves(coord + deltaPos.xy, iswater);
	float h2 = funWaterWaves(coord - deltaPos.xy, iswater);
	float h3 = funWaterWaves(coord + deltaPos.yx, iswater);
	float h4 = funWaterWaves(coord - deltaPos.yx, iswater);

	float xDelta = ((h1-h0)+(h0-h2));
	float yDelta = ((h3-h0)+(h0-h4));

	return vec3(vec2(xDelta,yDelta)*0.5, 0.5); //z = 1.0-0.5
}

vec3 funWaterParallax(vec3 pos, bool iswater){
	float getwave = funWaterWaves(pos.xz - pos.y, iswater);
	pos.xz += (getwave * viewVector.xy) * waterheight;
	return pos;
}

void main() {

	vec2 newTC = gl_FragCoord.xy / vec2(viewWidth, viewHeight);	
	vec4 vcolor = color;
	vec4 albedo = texture2D(texture, newTC.xy) * vcolor;
	bool isWater 		= normal.a > 0.79 && normal.a < 0.81;
	bool isIceglass 	= normal.a > 0.89 && normal.a < 0.91;
	float depth1 = texture2D(depthtex1, newTC.xy).x;
    float dhDepth = texture2D(dhDepthTex0, newTC.xy).x;
	vec3 newnormal = vec3(0.0);

	if(isWater) {
	#ifndef watertex
		#if MC_VERSION >= 11300			//color.rgb in 1.12.2 and below is only white
			albedo.rgb = vcolor.rgb;	//remove water texture, use color only.
		#else
			albedo = vec4(waterCR,waterCG,waterCB,waterA);
		#endif
		#ifdef customWaterC
			albedo = vec4(waterCR,waterCG,waterCB,waterA);
		#else
			albedo.a = 0.85;
		#endif
	#endif
	}

	albedo.rgb = pow(albedo.rgb, vec3(2.2));

	#ifdef TAA
		vec3 fragpos = utilScreenSpace(vec3(gl_FragCoord.xy*texelSize-offsets[framemod8]*texelSize*0.5, vec3(dhDepth, gl_FragCoord.xy*texelSize).x));
		float noise = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y + frameTimeCounter * 16.0);
	#else
		vec3 fragpos = utilScreenSpace(vec3(newTC, dhDepth));
		float noise = fract(gl_FragCoord.x * 0.618033988749895 + gl_FragCoord.y * 0.24412852441);
	#endif

	if(texture2D(depthtex1, gl_FragCoord.xy / vec2(viewWidth, viewHeight)).x < 1.0) discard;
	//if(texture2D(depthtex1, gl_FragCoord.xy / vec2(viewWidth, viewHeight)).x < 0.9995 && isIceglass) discard;
	if(dist > noise * 32.0 - 16.0 + far) albedo.a *= 1.25;

	//sky
	if(dhDepth >= 1.0) {

	//land
	} else {
	//shading
	float dif = clamp(dot(normal.xyz, normalize(shadowLightPosition)),0.0,1.0);
		  dif *= mix(skyL,1.0,clamp((eyeBrightnessSmooth.y/255.0-0.25)*4.0,0.0,1.0));
		  dif *= 0.5;
	vec3 finalLight = (dif*LightC*(SkyL2*skyL)*2.15+ambientC*(1.0/(SkyL2*skyL*0.5+0.5))*1.4)*0.63;
		  albedo.rgb *= finalLight;

	#ifndef iceRefl
		isIceglass = false;
	#endif

	if(isWater || isIceglass) {
		vec3 waterpos = worldpos;
		#ifdef WaterParallax
			waterpos = funWaterParallax(waterpos, isWater);
		#endif
		vec3 bump = funWaterBump(waterpos.xz - waterpos.y, isWater);
		newnormal = normalize(bump * tbnMatrix);
	}

	#if defined(IS_IRIS) || MC_VERSION >= 11604
	#ifdef Reflections
	if ((isWater || isIceglass) && isEyeInWater < 0.9) {
		float dither = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y);
			  dither = fract(dither + fract(frameTimeCounter * 128.0)) * 4.0;

		float F0 = isWater? 0.5 : 0.5 * (1.0 - albedo.a);
		vec3 reflectedVector = reflect(normalize(fragpos), newnormal);
		float normalDotEye   = dot(newnormal, normalize(fragpos));
		float fresnel        = pow(clamp(1.0 + normalDotEye, 0.0, 1.0), 4.0);
		fresnel              = mix(F0, 1.0, fresnel);

		vec2 skyMappingUV = utilVectorToSky(reflectedVector);
		vec3 skyrefl = texture2D(colortex6, skyMappingUV).rgb * 3.0;
		float skyLight = clamp((eyeBrightnessSmooth.y / 255.0 - 0.125) * 6.0, 0.0, 1.0);
		vec3 caveColor = vec3(0.01, 0.01, 0.015);
		skyrefl = mix(caveColor, skyrefl, skyLight);
		vec4 reflection = funReflections(reflectedVector, dither, fresnel, skyrefl.rgb);
	
		#ifndef waterRefl
			if(isWater)reflection = vec4(0.0);
		#endif

		if(isIceglass) { skyrefl = albedo.rgb * 1.5; albedo.rgb *= 1.5; }
		if(isWater) albedo.b *= 0.66;
		albedo.rgb = mix(albedo.rgb, mix(skyrefl.rgb, reflection.rgb, reflection.a), fresnel);

	#ifdef Whiteworld
		albedo.rgb += vec3(0.5);
	#endif
	}
	#endif
	#endif

	#if defined(IS_IRIS) || MC_VERSION >= 11604
	#ifdef Fog
		albedo.rgb = funFog(albedo.rgb*1.3, newTC, fragpos);
	#endif
	#endif
	}

	//albedo.rgb = (albedo.rgb * pow(eyeAdapt,0.88));
	albedo.rgb = pow(albedo.rgb, vec3(0.454));

	/* DRAWBUFFERS:41 */
	gl_FragData[0] = albedo;
	gl_FragData[1] = vec4(0.0, 0.0, normal.a, 1.0);
}