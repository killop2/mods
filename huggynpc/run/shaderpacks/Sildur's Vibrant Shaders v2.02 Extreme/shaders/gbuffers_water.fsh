#version 120
//buffer 41, 2 for legacy reflections
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
varying vec3 shadowData;
varying vec3 vertexShadowPos;
varying mat3 tbnMatrix;
varying vec4 normal;

varying float skyL;
varying float SkyL2;
varying float dist;

uniform sampler2D texture;
uniform sampler2D colortex4;
uniform sampler2D colortex6;
uniform sampler2D depthtex1;
uniform sampler2DShadow shadowtex0;
//uniform sampler2DShadow shadowtex1; //colored shadows in gbuffer are not that fast
//uniform sampler2D shadowcolor0;
uniform sampler2D noisetex;

uniform vec3 cameraPosition;
uniform vec3 shadowLightPosition;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;

uniform int isEyeInWater;
uniform float near;
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
	vec3 fogC = mix(fogFallback * 0.05, pow(texture2D(colortex4, newTC).rgb, vec3(2.2)), mix(skyL,1.0,clamp((eyeBrightnessSmooth.y/255.0-0.125)*4.0,0.0,1.0)));

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

#ifdef Reflections
varying mat3 vReflData;

vec2 utilVectorToSky(vec3 dir) {
    float u = atan(dir.x, dir.z) / 6.28318530718 + 0.5;	
    float v = asin(dir.y) / 3.14159265359 + 0.5;		
    return vec2(u, v);									
}

vec4 funReflections(vec3 dir, float noise, float fresnel, vec3 skyC) {
    float steps = 16.0;
    float maxDist = ((vReflData[0].z + dir.z * far * 1.73205) > -near) ? (-near - vReflData[0].z) / dir.z : far * 1.73205;
   
	vec3 targetPos = vReflData[0] + dir * maxDist;
    vec3 clipTarget = (vReflData[2] * targetPos + gbufferProjection[3].xyz) / -targetPos.z * 0.5 + 0.5;
	vec3 direction = vec3(normalize((clipTarget - vReflData[1]).xy), (clipTarget - vReflData[1]).z / max(length((clipTarget - vReflData[1]).xy), 0.0001));
    vec3 maxLengths = (step(0.0, direction) - vReflData[1]) / direction;
    vec3 refstep = direction * min(min(maxLengths.x, maxLengths.y), maxLengths.z) / steps;

    vec3 refpos = vReflData[1] + refstep * noise; 
    #ifdef TAA
		refpos.xy += offsets[framemod8] * texelSize * 0.5;
    #endif

	vec2 minMax = vec2(0.0, refpos.z + refstep.z * 0.5);
    vec4 reflC = vec4(skyC, 1.0);	//blend sky directly
    for (int i = 0; i <= int(steps); i++) {
        float depth = texture2D(depthtex1, refpos.xy).x;

        if (depth > 0.56 && depth <= max(minMax.y, minMax.x) && depth >= min(minMax.y, minMax.x)) {
            if (refpos.x > 0.0 && refpos.y > 0.0 && refpos.x < 1.0 && refpos.y < 1.0) {
				reflC.a = 1.0;
                reflC.rgb = pow(texture2D(colortex4, refpos.xy).rgb, vec3(2.2));
                break; 
            }
        }
        refpos += refstep;
        float linearDepth = (2.0 * near) / (far + near - refpos.z * (far - near));
        minMax.x = minMax.y - 0.00004 / linearDepth;
        minMax.y += refstep.z;
    }
	//reflC.rgb = mix(skyC, reflC.rgb, reflC.a);
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

#ifdef Caustics
float utilSmoothstep(float edge0, float edge1, float x) {
	float t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
	return t * t * (3.0 - 2.0 * t);
}

float funCaustics(vec2 coord) {
	//match real waves velocity and direction
    vec2 movement = abs(vec2(0.0, -frameTimeCounter * 0.31365));
    vec2 alignedCoord = coord * rmatrix(-1.35);
    vec2 p = alignedCoord * 0.5;

    float causticAccumulator = 0.0;
    float scale = 1.0;
    float slowTime = frameTimeCounter * 2.0;
    for (int i = 0; i < 3; i++) {
        vec2 p0 = p * rmatrix(2.1 + float(i) * 0.14) - movement * (4.5 / scale);
        	 p0.y *= 4.0;

        float waveX = abs(sin(p0.x + cos(p0.y)));
        float waveY = abs(cos(p0.y + sin(p0.x)));

        p.x += cos(p0.y + waveX + slowTime) * 0.32;
        p.y += sin(p0.x + waveY - slowTime) * 0.22;

        float layerNetwork = 1.0 - (waveX + waveY) * 0.5;
        causticAccumulator += layerNetwork * scale;

        p *= 1.5;
        scale *= 0.55;
    }
    float finalCaustic = pow(max(0.0, causticAccumulator * 0.55), 1.5) * 3.0; // 5.0, 5.0
	#if MC_VERSION < 11300
		finalCaustic *= 0.5;
	#endif
    return clamp(finalCaustic, 0.0, 10.0) * causticsStrength;
}

#ifdef Refraction
vec2 funWaterRefraction(vec2 coord) {
	//match real waves velocity and direction
    vec2 movement = abs(vec2(0.0, -frameTimeCounter * 0.31365));
    vec2 alignedCoord = coord * rmatrix(-1.35);
    vec2 p = alignedCoord * 0.5;
    
	vec2 refractionOffset = vec2(0.0);
    float scale = 1.0;
    float slowTime = frameTimeCounter * 2.0;
    for (int i = 0; i < 3; i++) {
        vec2 p0 = p * rmatrix(2.1 + float(i) * 0.14) - movement * (4.5 / scale);
             p0.y *= 4.0;
        float waveX = abs(sin(p0.x + cos(p0.y)));
        float waveY = abs(cos(p0.y + sin(p0.x)));
        float forceX = cos(p0.y + waveX + slowTime);
        float forceY = sin(p0.x + waveY - slowTime);

        p.x += forceX * 0.32;
        p.y += forceY * 0.22;
        refractionOffset.x += forceX * scale;
        refractionOffset.y += forceY * scale;

        p *= 1.5;
        scale *= 0.55;
    }
    float refractionStrength = 0.003;
    return refractionOffset * refractionStrength;
}
#endif
#endif

void main() {

	vec2 newTC = gl_FragCoord.xy / vec2(viewWidth, viewHeight);	
	vec4 vcolor = color;
	vec4 albedo = texture2D(texture, texcoord.xy) * vcolor;
	bool isWater 		= normal.a > 0.79 && normal.a < 0.81;
	bool isIceglass 	= normal.a > 0.89 && normal.a < 0.91;
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
		//render shadows for caustics but not on water plane
		vec3 floorShadow = funShadows(noise, skyL, fragpos, normal.xyz);
		vec3 finalShadow = isWater ? vec3(0.1) : floorShadow;
		
		#ifdef Caustics
		if (isWater) {
			#ifdef Refraction
   				newTC += funWaterRefraction(worldpos.xz);
			#endif
			//setup underwater floor
			float floorDepth = texture2D(depthtex1, newTC).r;
			vec4 screenPos = vec4(newTC * 2.0 - 1.0, floorDepth * 2.0 - 1.0, 1.0);
			vec4 viewPos = gbufferProjectionInverse * screenPos;
				 viewPos /= viewPos.w;
			vec4 uwWorldPos = gbufferModelViewInverse * viewPos;
				 uwWorldPos.xyz += cameraPosition;
			float underwaterDepth = max(0.0, worldpos.y - uwWorldPos.y);

			float causticIntensity = funCaustics(uwWorldPos.xz);
			float shallowFade = utilSmoothstep(0.0, 0.3, underwaterDepth);
			float deepFade = pow(clamp(1.0 - (underwaterDepth / 18.0), 0.0, 1.0), 8.0);
			float depthMask = shallowFade * deepFade;

			float skyLight = clamp((eyeBrightnessSmooth.y / 255.0 - 0.125) * 6.0, 0.0, 1.0);
			vec3 causticSunlight = vec3(causticIntensity * skyLight * depthMask * 4.0) * floorShadow;

			finalShadow += causticSunlight * (1.0 - albedo.a);
		}
		#endif

		vec3 finalLight = (finalShadow*LightC*(SkyL2*skyL)*2.15+ambientC*(1.0/(SkyL2*skyL*0.5+0.5))*1.4)*0.63;
		albedo.rgb *= finalLight;
	#else
		float dif = clamp(dot(normal.xyz, normalize(shadowLightPosition)),0.0,1.0);
			  dif *= mix(skyL,1.0,clamp((eyeBrightnessSmooth.y/255.0-0.25)*4.0,0.0,1.0));
			  dif *= 0.5;
		vec3 finalLight = (dif*LightC*(SkyL2*skyL)*2.15+ambientC*(1.0/(SkyL2*skyL*0.5+0.5))*1.4)*0.63;
		albedo.rgb *= finalLight;
	#endif

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

	#if (defined(IS_IRIS) || MC_VERSION >= 11604) && !defined VOXY
	#ifdef Fog
		albedo.rgb = funFog(albedo.rgb, newTC, fragpos);
	#endif
	#endif
	}

	//albedo.rgb = (albedo.rgb * pow(eyeAdapt,0.88));
	albedo.rgb = pow(albedo.rgb, vec3(0.454));

	/* DRAWBUFFERS:41 */
	gl_FragData[0] = albedo;
	gl_FragData[1] = vec4(0.0, 0.0, normal.a, 1.0);
	#if !defined(IS_IRIS) && MC_VERSION < 11604	//optifine versions older than 1.16.4 don't support reflections / reading textures in gbuffers correctly.
	/* DRAWBUFFERS:412 */
	gl_FragData[2] = vec4(newnormal.xyz*0.5+0.5, 1.0);
	#endif
}