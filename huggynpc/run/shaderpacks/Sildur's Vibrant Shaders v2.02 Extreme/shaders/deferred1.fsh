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


#define AA_settings
#define Reflection_settings
#ifdef VOXY
#define gbuffers_water
#define Fog_settings
#endif
#include "shaders.settings"

varying vec2 texcoord;
uniform sampler2D colortex4;

#ifdef Reflections
#if defined metallicRefl || defined polishedRefl || defined RainReflections || defined VOXY
uniform sampler2D colortex1;	// lightmap, mats
uniform sampler2D colortex2;	// normal, PCSS
uniform sampler2D colortex6;	// sky panorama
uniform sampler2D depthtex0;
uniform sampler2D depthtex2;

uniform vec3 cameraPosition;
uniform mat4 gbufferProjection;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferProjectionInverse;

uniform float far;
uniform float near;
uniform float wetness;
uniform float rainStrength;	
uniform float frameTimeCounter;
uniform ivec2 eyeBrightnessSmooth;

uniform float BiomeTemp;
uniform int isEyeInWater;

vec3 utilScreenSpace(vec3 pos) {
	vec4 iProjDiag = vec4(gbufferProjectionInverse[0].x, gbufferProjectionInverse[1].y, gbufferProjectionInverse[2].zw);
	vec3 p3 = pos * 2.0 - 1.0;
    vec4 fragposition = iProjDiag * p3.xyzz + gbufferProjectionInverse[3];
    return fragposition.xyz / fragposition.w;
}

uniform float viewWidth;
uniform float viewHeight;

#ifdef TAA
uniform int framemod8;

vec2 texelSize = vec2(1.0/viewWidth,1.0/viewHeight);
const vec2[8] offsets = vec2[8](vec2(1./8.,-3./8.),
								vec2(-1.,3.)/8.,
								vec2(5.0,1.)/8.,
								vec2(-3,-5.)/8.,
								vec2(-5.,5.)/8.,
								vec2(-7.,-1.)/8.,
								vec2(3,7.)/8.,
								vec2(7.,-7.)/8.);
#endif

vec2 utilVectorToSky(vec3 dir) {
    float u = atan(dir.x, dir.z) / 6.28318530718 + 0.5;
    float v = asin(dir.y) / 3.14159265359 + 0.5;
    return vec2(u, v);
}

vec4 funReflections(vec3 dir, vec3 position, float noise, float fresnel, vec3 skyC) {
	float steps = 16.0;
    float maxDist = ((position.z + dir.z * far * 1.73205) > -near) ? (-near - position.z) / dir.z : far * 1.73205;

    vec3 projDiag = vec3(gbufferProjection[0].x, gbufferProjection[1].y, gbufferProjection[2].z);
    vec3 clipPos = (projDiag * position + gbufferProjection[3].xyz) / -position.z * 0.5 + 0.5;
   
    vec3 targetPos = position + dir * maxDist;
    vec3 clipTarget = (projDiag * targetPos + gbufferProjection[3].xyz) / -targetPos.z * 0.5 + 0.5;
    vec3 direction = vec3(normalize((clipTarget - clipPos).xy), (clipTarget - clipPos).z / max(length((clipTarget - clipPos).xy), 0.0001));
    vec3 maxLengths = (step(0.0, direction) - clipPos) / direction;
    vec3 refstep = direction * min(min(maxLengths.x, maxLengths.y), maxLengths.z) / steps;

    vec3 refpos = clipPos + refstep * noise;
    #ifdef TAA
		refpos.xy += offsets[framemod8] * texelSize * 0.5;
    #endif

	vec2 minMax = vec2(0.0, refpos.z + refstep.z * 0.5);
    vec4 reflC = vec4(skyC.rgb, 1.0);
    for (int i = 0; i <= int(steps); i++) {
        float depth = texture2D(depthtex0, refpos.xy).x;

        if (depth > 0.56 && depth <= max(minMax.y, minMax.x) && depth >= min(minMax.y, minMax.x)) {
            if (refpos.x > 0.0 && refpos.y > 0.0 && refpos.x < 1.0 && refpos.y < 1.0) {
                reflC.a = 1.0;
                reflC.rgb = texture2D(colortex4, refpos.xy).rgb;
                break;
            }
        }

        refpos += refstep;
        float linearDepth = (2.0 * near) / (far + near - refpos.z * (far - near));
        minMax.x = minMax.y - 0.00004 / linearDepth;
        minMax.y += refstep.z;
    }
    //reflC.rgb = mix(skyC.rgb, reflC.rgb, reflC.a);
    return reflC;
}

#ifdef RainReflections
float utilSmoothstep(float edge0, float edge1, float x) {
	float t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
	return t * t * (3.0 - 2.0 * t);
}

vec2 funRainRipples(vec2 uv, float time) {
    float t = time * 3.0; 
    vec2 p = uv * 24.0;
	vec2 i = floor(p);
	vec2 f = fract(p);
	vec2 rippleNormal = vec2(0.0);
    
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 cellOffset = vec2(float(x), float(y));
            vec2 randPos = fract(sin(vec2(dot(i + cellOffset, vec2(127.1, 311.7)), dot(i + cellOffset, vec2(269.5, 183.3)))) * 43758.5453);
            
            vec2 distVector = f - (cellOffset + 0.1 + 0.8 * randPos);
            float dist = length(distVector);
			float dropAge = fract(t + randPos.x);
            
            float ringWeight = utilSmoothstep(0.12, 0.0, abs(dist - dropAge * 0.75)) * utilSmoothstep(0.75, 0.0, dropAge) * dropAge;
            rippleNormal += normalize(distVector + 0.0001) * sin(dist * 42.0 - t * 24.0) * ringWeight;
        }
    }
    return rippleNormal * 2.0;
}
#endif

vec3 utilDecode (vec2 enc){
    vec2 fenc = enc*4.0-2.0;
    float f = dot(fenc,fenc);
    float g = sqrt(1.0-f/4.0);
    vec3 n;
    n.xy = fenc*g;
    n.z = 1.0-f/2.0;
    return n;
}

#ifdef VOXY
uniform sampler2D noisetex;
uniform mat4 vxModelViewInv;
uniform mat4 vxModelView;
varying vec4 color;

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
vec3 funWaterParallax(vec3 pos, vec2 view, bool iswater){
	float getwave = funWaterWaves(pos.xz - pos.y, iswater);
	pos.xz += (getwave * view) * waterheight;
	return pos;
}

#ifdef Fog
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform int vxRenderDistance;

vec3 funFog(vec3 albedo, vec3 fragpos) {
	vec3 fogC = texture2D(colortex4, texcoord.xy).rgb;
	#ifdef defskybox
		fogC = pow(fogColor * 0.7, vec3(3.2));
	#endif

	float dist = length(fragpos);
	float newFar = max(far, float(vxRenderDistance * 16.0)) * 256.0;
	float fogScaling = (isEyeInWater == 1.0) ? (uFogDensity - 1.0) * 11.5 : (wFogDensity - 1.0) * 11.5;
	float fogDistance = dist / newFar * 12.5 - 11.5 + fogScaling;

	return mix(albedo, fogC, clamp(max((dist - fogStart) / max(fogEnd - fogStart, 0.0001), fogDistance), 0.0, 1.0));
}
#endif
#endif

#endif
#endif

void main() {

	vec4 albedo = texture2D(colortex4, texcoord.xy);
	
    #ifdef Reflections
    #if defined metallicRefl || defined polishedRefl || defined RainReflections  || defined VOXY
    vec3 normal = utilDecode(texture2D(colortex2, texcoord.xy).xy);
	float depth0 = texture2D(depthtex0, texcoord.xy).x;
    float mats = texture2D(colortex1, texcoord.xy).b;
	bool isHand = texture2D(depthtex2, texcoord.xy).x > depth0;
	bool isMetallic = mats > 0.39 && mats < 0.41;
	bool isPolished = mats > 0.49 && mats < 0.51;

	#ifdef VOXY
		bool isWater = mats > 0.79 && mats < 0.81;
		bool isIceglass = mats > 0.89 && mats < 0.91;
	#endif

	#ifndef polishedRefl
		isPolished = false;
	#endif
	#ifndef metallicRefl	
		isMetallic = false;
	#endif

	#ifdef TAA
		vec3 fragpos0 = utilScreenSpace(vec3(gl_FragCoord.xy*texelSize-offsets[framemod8]*texelSize*0.5, texture2D(depthtex0, gl_FragCoord.xy*texelSize).x));
		vec3 reflnoise = vec3(texcoord.xy * 1000.0, fract(fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y + float(framemod8) * 0.125) * 13.1313));
	#else
		vec3 fragpos0 = utilScreenSpace(vec3(texcoord, depth0));
		float noise = fract(gl_FragCoord.x * 0.618033988749895 + gl_FragCoord.y * 0.24412852441);		
		vec3 reflnoise = vec3(texcoord.xy * 1000.0, fract(noise * 13.1313));
	#endif
	
	//sky
	if(depth0 >= 1.0) {

	//land
	} else {
		vec3 newnormal = vec3(fract(sin(dot(reflnoise.xyz, vec3(12.9898, 78.233, 45.164))) * 43758.5453),
							  fract(sin(dot(reflnoise.yzx, vec3(34.1251, 19.873, 82.341))) * 28941.1234),
							  fract(sin(dot(reflnoise.zxy, vec3(56.7891, 64.215, 12.987))) * 91235.6543)) * 2.0 - 1.0;

	#ifdef RainReflections
		#ifdef BiomeCheck
			bool isRaining = (BiomeTemp >= 0.15) && (BiomeTemp <= 1.0) && wetness > 0.001 && (float(eyeBrightnessSmooth.y) * 0.0039215686) > 0.85  && isEyeInWater < 0.9 && !isHand;
		#else
			bool isRaining = wetness > 0.001 && (float(eyeBrightnessSmooth.y) * 0.0039215686) > 0.85 && isEyeInWater < 0.9 && !isHand;
		#endif
	if (isRaining) {
		vec3 worldPos = mat3(gbufferModelViewInverse) * fragpos0 + gbufferModelViewInverse[3].xyz + cameraPosition;

		float puddleMask = 0.0;
		float scale = 0.08;
		float weight = 0.75;
		for (int l = 0; l < 2; l++) {
			vec2 p = worldPos.xz * scale;
			vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
			vec4 dotX = i.xxxx + vec4(0.0, 1.0, 0.0, 1.0);
			vec4 dotY = i.yyyy + vec4(0.0, 0.0, 1.0, 1.0);
			vec4 hashes = fract(sin(dotX * 127.1 + dotY * 311.7) * 43758.5453);
		
			puddleMask += mix(mix(hashes.x, hashes.y, f.x), mix(hashes.z, hashes.w, f.x), f.y) * weight;
			scale = 0.32; 
			weight = 0.25;
		}
		puddleMask = clamp(puddleMask, 0.0, 1.0);

		//speed up initial wetness
		float targetIntensity = pow(wetness, 0.5) * 0.65 - 0.05;
		if (puddleMask < targetIntensity) {
			float F0 = 0.09;	
			float t = clamp((targetIntensity - puddleMask) / 0.08, 0.0, 1.0);
			float edgeSoftness = pow(t * t * (3.0 - 2.0 * t), 0.5);

			float dither = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y);
				  dither = fract(dither + fract(frameTimeCounter * 128.0)) * 4.0;

			vec2 proceduralRipples = funRainRipples(worldPos.xz, frameTimeCounter) * rainStrength;
			vec3 rippleNormalOffset = vec3(proceduralRipples.x, proceduralRipples.y, 0.0);

			float roughness = rainNoise + 0.05 * (1.0 - rainStrength);
			normal = normalize(normal + newnormal * roughness + rippleNormalOffset);

			vec3 reflectedVector = reflect(normalize(fragpos0), normal);
			float normalDotEye   = dot(normal, normalize(fragpos0));
			float fresnel        = pow(clamp(1.0 + normalDotEye, 0.0, 1.0), 4.0);
			fresnel              = mix(F0, 1.0, fresnel) * 0.5;

			vec2 skyMappingUV = utilVectorToSky(reflectedVector);
			vec3 skyrefl = pow(texture2D(colortex6, skyMappingUV).rgb, vec3(0.325));

			vec4 reflection = funReflections(reflectedVector, fragpos0, dither, fresnel, skyrefl);
			albedo.rgb = mix(albedo.rgb, mix(albedo.rgb, reflection.rgb, fresnel), edgeSoftness);
		}
	}
	#endif

    if (isMetallic || isPolished) {
		float F0 = 0.09;
		float roughness = metalNoise;
		normal = normalize(normal + newnormal * roughness);

        vec3 reflectedVector = reflect(normalize(fragpos0), normal);
        float normalDotEye   = dot(normal, normalize(fragpos0));
        float fresnel        = pow(clamp(1.0 + normalDotEye, 0.0, 1.0), 4.0);
        fresnel              = mix(F0, 1.0, fresnel);

		vec2 skyMappingUV = utilVectorToSky(reflectedVector);
		vec3 skyrefl = pow(texture2D(colortex6, skyMappingUV).rgb, vec3(0.325));
		float skyLight = clamp((eyeBrightnessSmooth.y / 255.0 - 0.125) * 6.0, 0.0, 1.0);
		vec3 caveColor = vec3(0.01, 0.01, 0.015);
		skyrefl = mix(caveColor, skyrefl, skyLight);

        vec4 reflection = funReflections(reflectedVector, fragpos0, 1.0, fresnel, skyrefl);
        albedo.rgb = mix(albedo.rgb, reflection.rgb, fresnel);
	}
	}
    #endif

	#ifdef VOXY
	if ((isWater || isIceglass) && isEyeInWater < 0.9) {
		vec3 worldPos = mat3(vxModelViewInv) * fragpos0 + vxModelViewInv[3].xyz;
		mat3 tbnMatrix = mat3(vxModelView[0].x, vxModelView[2].x, normal.x,
							  vxModelView[0].y, vxModelView[2].y, normal.y,
							  vxModelView[0].z, vxModelView[2].z, normal.z);
		vec3 waterpos = worldPos + cameraPosition;
		#ifdef WaterParallax
			waterpos = funWaterParallax(waterpos, worldPos.xz / length(worldPos.xz) * 8.25, isWater);
		#endif
		vec3 vxNormal = clamp(normalize(funWaterBump(waterpos.xz - waterpos.y, isWater) * tbnMatrix), vec3(-1.0), vec3(1.0));

		if(isWater){
			albedo.rgb = pow(albedo.rgb*1.5, vec3(2.2));
			#ifndef watertex
				#ifdef customWaterC
					albedo = vec4(waterCR,waterCG,waterCB,waterA);
				#else
					albedo.a = 0.85;
				#endif
			#endif
		}

		float F0 = isWater? 0.5 : 0.5 * (1.0 - albedo.a);
		vec3 reflectedVector = reflect(normalize(fragpos0), vxNormal);
		float normalDotEye   = dot(vxNormal, normalize(fragpos0));
		float fresnel        = pow(clamp(1.0 + normalDotEye, 0.0, 1.0), 4.0);
		fresnel              = mix(F0, 1.0, fresnel);

		vec2 skyMappingUV = utilVectorToSky(reflectedVector);
		vec3 skyrefl = pow(texture2D(colortex6, skyMappingUV).rgb, vec3(0.325));
		float skyLight = clamp((eyeBrightnessSmooth.y / 255.0 - 0.125) * 6.0, 0.0, 1.0);
		vec3 caveColor = vec3(0.01, 0.01, 0.015);
		skyrefl = mix(caveColor, skyrefl, skyLight);

        vec4 reflection = funReflections(reflectedVector, fragpos0, 1.0, fresnel, skyrefl);

		#ifndef waterRefl
			if(isWater)reflection = vec4(0.0);
		#endif

		if(isIceglass) { skyrefl = albedo.rgb * 1.5; albedo.rgb *= 0.75; }
		albedo.rgb = mix(albedo.rgb, mix(skyrefl.rgb, reflection.rgb, reflection.a), fresnel);

		#ifdef Whiteworld
			albedo.rgb += vec3(0.5);
		#endif

		#ifdef Fog
			albedo.rgb = funFog(albedo.rgb, fragpos0);
		#endif
	}
	#endif

    #endif

	gl_FragData[0] = albedo;
}