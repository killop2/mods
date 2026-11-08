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


#define composite01
#define AA_settings
#define Fog_settings
#define Reflection_settings
#include "shaders.settings"

varying vec2 texcoord;

uniform sampler2D colortex1;	// lightmap, mats
uniform sampler2D colortex3;	// empty, Godrays, Volumetric
uniform sampler2D colortex4;	// final deferred and water
uniform sampler2D colortex5;	// bloom
uniform sampler2D colortex6;	// panorama sky
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;

uniform float far;
uniform float near;
uniform float viewWidth;
uniform float viewHeight;
uniform float rainStrength;
uniform int isEyeInWater;
uniform vec3 shadowLightPosition;
uniform mat4 gbufferProjectionInverse;

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
	float darknessFactor = 0.0;
	float darknessLightFactor = 0.0;
#endif

vec3 funFog(vec3 albedo, vec3 fragpos) {
	#if defined(IS_IRIS) || MC_VERSION >= 11802	
		vec3 fogFallback = fogColor;
	#else
		vec3 fogFallback = gl_Fog.color.rgb;
	#endif

	vec3 fogC = pow(texture2D(colortex4, texcoord.xy).rgb, vec3(2.2));
	#ifdef defskybox
		fogC = pow(fogFallback * 0.7, vec3(3.2));
	#endif

	float dist = length(fragpos);
	float newFar = far;
	float fogScaling = (isEyeInWater == 1.0) ? (uFogDensity - 1.0) * 11.5 : (wFogDensity - 1.0) * 11.5;
	float fogDistance = dist / newFar * 12.5 - 11.5 + fogScaling;

	float toggleFog = 1.0;
	#ifndef Underwater_Fog
    	if (isEyeInWater == 1.0) toggleFog = 0.0;
	#endif

	if(isEyeInWater == 1.0) fogC = fogFallback * 0.2 * fogC.b;
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

#if defined TAA || defined Reflections
uniform int framemod8;
uniform float frameTimeCounter;
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

#if defined Volumetric_Lighting || defined Godrays || defined Reflections
vec2 utilVectorToSky(vec3 dir) {
    float u = atan(dir.x, dir.z) / 6.28318530718 + 0.5;
    float v = asin(dir.y) / 3.14159265359 + 0.5;
    return vec2(u, v);
}

uniform vec3 sunPosition;
uniform int worldTime;
//uniform vec3 upPosition;

vec3 funLightcolor(bool isGodrays) {
    vec2 lightUV = utilVectorToSky(normalize(sunPosition)); //moon is hardcoded so sampling just the sun should be enough vs shadowlightposition
   
/*	//dynamically sample sunpos
    vec3 sunVec = normalize(sunPosition);
    vec3 upVec = normalize(upPosition);
    float SdotU = dot(sunVec, upVec);
    float sunVisibility = pow(clamp(SdotU + 0.15, 0.0, 0.15) / 0.15, 4.0);
    float dynamicOffset = mix(0.002, 0.0004, sunVisibility);
    vec3 offset = vec3(dynamicOffset, -dynamicOffset, 0.0);
*/
	//sample sunpos
    vec3 offset = vec3(0.0004, -0.0004, 0.0);
    vec3 pixelCenter = texture2DLod(colortex6, lightUV, 0).rgb;
    vec3 pixelLeft   = texture2DLod(colortex6, lightUV + offset.yz, 0).rgb;
    vec3 pixelRight  = texture2DLod(colortex6, lightUV + offset.xz, 0).rgb;
    vec3 pixelTop    = texture2DLod(colortex6, lightUV + offset.zx, 0).rgb;
    vec3 pixelBottom = texture2DLod(colortex6, lightUV + offset.zy, 0).rgb;

    vec3 peakLightColor = max(pixelCenter, max(max(pixelLeft, pixelRight), max(pixelTop, pixelBottom)));
    float lightLengthSq = dot(peakLightColor, peakLightColor);
    
    vec3 sampledLightColor = vec3(1.0);
    if (lightLengthSq > 0.000001) {
        sampledLightColor = peakLightColor * inversesqrt(lightLengthSq);
    }

    vec3 sunColor = sampledLightColor;

    //red color shift
    float w = max(sunColor.r, max(sunColor.g, sunColor.b));
    if (w > 0.0001) sunColor = mix(sunColor / w, vec3(2.5, 0.35, 0.1), 0.6) * w;
	
    vec3 moonColor = vec3(0.25, 0.38, 0.55);
	
	//sun moon switch, extra switch for godrays / vl.
	float blendFactor = 0.0;
    if (isGodrays) {
        float truepos = sign(-sunPosition.z) * 1.0; 
        blendFactor = (truepos * -1.0 + 1.0) / 2.0;
    } else if (worldTime > 12750 && worldTime < 23250) { blendFactor = 1.0; }
    
    vec3 finalLightColor = mix(sunColor, moonColor, blendFactor);
    return finalLightColor;
}

#endif

#ifdef Lens_Flares
uniform mat4 gbufferProjection;
uniform vec3 sunVec;
uniform vec3 upVec;
uniform float aspectRatio;

vec3 funLensFlare(vec3 color) {
	//Adjust global flare settings
	float lightColor = Lens_Flares_Strength;	//used to be vec3 sqrt(lightColor) from vertex, re-use as global strength instead
	float flarescale = 1.0;
	float flaremultR = 0.04*lightColor;
	float flaremultG = 0.05*lightColor;
	float flaremultB = 0.075*lightColor;

	//depth detection
    vec4 projectedLight = gbufferProjection * vec4(shadowLightPosition, 1.0);
    	 projectedLight.xyz /= projectedLight.w;

    if (projectedLight.z > 1.0 || projectedLight.w <= 0.0) return color;
    vec2 lPos = projectedLight.xy * 0.5 + 0.5;
    float sunVisibility = pow(clamp(dot(sunVec, upVec) + 0.15, 0.0, 0.15) / 0.15, 4.0);
    float comp = 1.0 - near / far / far;
    float occ = (step(comp, texture2D(depthtex0, lPos).x) + 
                 step(comp, texture2D(depthtex0, lPos + vec2(-0.005, 0.0)).x) + 
                 step(comp, texture2D(depthtex0, lPos + vec2(0.005, 0.0)).x) + 
                 step(comp, texture2D(depthtex0, lPos + vec2(0.0, 0.005)).x) + 
                 step(comp, texture2D(depthtex0, lPos + vec2(0.0, -0.005)).x)) / 5.0;
    float sunmask = (1.0 - rainStrength) * occ;
    if (sunmask * sunVisibility <= 0.01) return color;
/*	//OG ancient lens flare code:
	//disabled all 3 sun glows for moon
	
	//Small sun glare/glow
		vec2 flare1scale = vec2(1.7*flarescale, 1.7*flarescale);
		float flare1pow = 12.0;
		vec2 flare1pos = vec2(lPos.x*aspectRatio*flare1scale.x, lPos.y*flare1scale.y);
		float flare1 = distance(flare1pos, vec2(texcoord.x*aspectRatio*flare1scale.x, texcoord.y*flare1scale.y));
              flare1 = 0.5 - flare1;
              flare1 = clamp(flare1, 0.0, 10.0)  ;
              flare1 *= sunmask;
              flare1 = pow(flare1, 1.8);
              flare1 *= flare1pow;
		if(sunVisibility > 0.2){
			  color.r += flare1*1.0*flaremultR;
			  color.g += flare1*0.1*flaremultG;
			  color.b += flare1*0.0*flaremultB;
		} else {
			  color.r += flare1*0.0*flaremultR;
			  color.g += flare1*0.1*flaremultG;
			  color.b += flare1*0.5*flaremultB;
		}
	//Huge sun glare/glow
		vec2 flare1Bscale = vec2(0.5*flarescale, 0.5*flarescale);
		float flare1Bpow = 6.0;
		vec2 flare1Bpos = vec2(lPos.x*aspectRatio*flare1Bscale.x, lPos.y*flare1Bscale.y);
		float flare1B = distance(flare1Bpos, vec2(texcoord.x*aspectRatio*flare1Bscale.x, texcoord.y*flare1Bscale.y));
              flare1B = 0.5 - flare1B;
              flare1B = clamp(flare1B, 0.0, 10.0)  ;
              flare1B *= sunmask;
              flare1B = pow(flare1B, 1.8);
			  flare1B *= flare1Bpow;
			  color.r += flare1B*1.0*flaremultR;
			  color.g += flare1B*0.1*flaremultG;
			  color.b += flare1B*0.0*flaremultB;
*/
	//Far blue flare MAIN
		vec2 flare3scale = vec2(2.0*flarescale, 2.0*flarescale);
		float flare3pow = 0.7;
		float flare3fill = 10.0;
		float flare3offset = -0.5;
		vec2 flare3pos = vec2(  ((1.0 - lPos.x)*(flare3offset + 1.0) - (flare3offset*0.5))  *aspectRatio*flare3scale.x,  ((1.0 - lPos.y)*(flare3offset + 1.0) - (flare3offset*0.5))  *flare3scale.y);
		float flare3 = distance(flare3pos, vec2(texcoord.x*aspectRatio*flare3scale.x, texcoord.y*flare3scale.y));
              flare3 = 0.5 - flare3;
              flare3 = clamp(flare3*flare3fill, 0.0, 1.0)  ;
              flare3 = sin(flare3*1.57075);
              flare3 *= sunmask;
              flare3 = pow(flare3, 1.1);
              flare3 *= flare3pow;
	//subtract from blue flare
		vec2 flare3Bscale = vec2(1.4*flarescale, 1.4*flarescale);
		float flare3Bpow = 1.0;
		float flare3Bfill = 2.0;
		float flare3Boffset = -0.65f;
		vec2 flare3Bpos = vec2(  ((1.0 - lPos.x)*(flare3Boffset + 1.0) - (flare3Boffset*0.5))  *aspectRatio*flare3Bscale.x,  ((1.0 - lPos.y)*(flare3Boffset + 1.0) - (flare3Boffset*0.5))  *flare3Bscale.y);
		float flare3B = distance(flare3Bpos, vec2(texcoord.x*aspectRatio*flare3Bscale.x, texcoord.y*flare3Bscale.y));
              flare3B = 0.5 - flare3B;
              flare3B = clamp(flare3B*flare3Bfill, 0.0, 1.0)  ;
              flare3B = sin(flare3B*1.57075);
              flare3B *= sunmask;
              flare3B = pow(flare3B, 0.9);
              flare3B *= flare3Bpow;
              flare3 = clamp(flare3 - flare3B, 0.0, 10.0);
              color.r += flare3*0.5*flaremultR;
              color.g += flare3*0.3*flaremultG;
              color.b += flare3*1.0*flaremultB;
	//Far blue flare MAIN 2
		vec2 flare3Cscale = vec2(3.2*flarescale, 3.2*flarescale);
		float flare3Cpow = 1.4;
		float flare3Cfill = 10.0;
		float flare3Coffset = -0.0;
		vec2 flare3Cpos = vec2(  ((1.0 - lPos.x)*(flare3Coffset + 1.0) - (flare3Coffset*0.5))  *aspectRatio*flare3Cscale.x,  ((1.0 - lPos.y)*(flare3Coffset + 1.0) - (flare3Coffset*0.5))  *flare3Cscale.y);
		float flare3C = distance(flare3Cpos, vec2(texcoord.x*aspectRatio*flare3Cscale.x, texcoord.y*flare3Cscale.y));
              flare3C = 0.5 - flare3C;
              flare3C = clamp(flare3C*flare3Cfill, 0.0, 1.0)  ;
              flare3C = sin(flare3C*1.57075);
              flare3C = pow(flare3C, 1.1);
              flare3C *= flare3Cpow;
	//subtract from blue flare
		vec2 flare3Dscale = vec2(2.1*flarescale, 2.1*flarescale);
		float flare3Dpow = 2.7;
		float flare3Dfill = 1.4;
		float flare3Doffset = -0.05f;
		vec2 flare3Dpos = vec2(  ((1.0 - lPos.x)*(flare3Doffset + 1.0) - (flare3Doffset*0.5))  *aspectRatio*flare3Dscale.x,  ((1.0 - lPos.y)*(flare3Doffset + 1.0) - (flare3Doffset*0.5))  *flare3Dscale.y);
		float flare3D = distance(flare3Dpos, vec2(texcoord.x*aspectRatio*flare3Dscale.x, texcoord.y*flare3Dscale.y));
              flare3D = 0.5 - flare3D;
              flare3D = clamp(flare3D*flare3Dfill, 0.0, 1.0)  ;
              flare3D = sin(flare3D*1.57075);
              flare3D = pow(flare3D, 0.9);
              flare3D *= flare3Dpow;
              flare3C = clamp(flare3C - flare3D, 0.0, 10.0);
              flare3C *= sunmask;
              color.r += flare3C*0.5*flaremultR;
              color.g += flare3C*0.3*flaremultG;
              color.b += flare3C*1.0*flaremultB;
	//far small pink flare
		vec2 flare4scale = vec2(4.5*flarescale, 4.5*flarescale);
		float flare4pow = 0.3;
		float flare4fill = 3.0;
		float flare4offset = -0.1;
		vec2 flare4pos = vec2(  ((1.0 - lPos.x)*(flare4offset + 1.0) - (flare4offset*0.5))  *aspectRatio*flare4scale.x,  ((1.0 - lPos.y)*(flare4offset + 1.0) - (flare4offset*0.5))  *flare4scale.y);
		float flare4 = distance(flare4pos, vec2(texcoord.x*aspectRatio*flare4scale.x, texcoord.y*flare4scale.y));
              flare4 = 0.5 - flare4;
              flare4 = clamp(flare4*flare4fill, 0.0, 1.0)  ;
              flare4 = sin(flare4*1.57075);
              flare4 *= sunmask;
              flare4 = pow(flare4, 1.1);
              flare4 *= flare4pow;
              color.r += flare4*1.6*flaremultR;
              color.g += flare4*0.0*flaremultG;
              color.b += flare4*1.8*flaremultB;
	//far small pink flare2
		vec2 flare4Bscale = vec2(7.5*flarescale, 7.5*flarescale);
		float flare4Bpow = 0.4;
		float flare4Bfill = 2.0;
		float flare4Boffset = 0.0;
		vec2 flare4Bpos = vec2(  ((1.0 - lPos.x)*(flare4Boffset + 1.0) - (flare4Boffset*0.5))  *aspectRatio*flare4Bscale.x,  ((1.0 - lPos.y)*(flare4Boffset + 1.0) - (flare4Boffset*0.5))  *flare4Bscale.y);
		float flare4B = distance(flare4Bpos, vec2(texcoord.x*aspectRatio*flare4Bscale.x, texcoord.y*flare4Bscale.y));
              flare4B = 0.5 - flare4B;
              flare4B = clamp(flare4B*flare4Bfill, 0.0, 1.0)  ;
              flare4B = sin(flare4B*1.57075);
              flare4B *= sunmask;
              flare4B = pow(flare4B, 1.1);
              flare4B *= flare4Bpow;
              color.r += flare4B*1.4*flaremultR;
              color.g += flare4B*0.0*flaremultG;
              color.b += flare4B*1.8*flaremultB;
	//far small pink flare3
		vec2 flare4Cscale = vec2(37.5*flarescale, 37.5*flarescale);
		float flare4Cpow = 2.0;
		float flare4Cfill = 2.0;
		float flare4Coffset = -0.3;
		vec2 flare4Cpos = vec2(  ((1.0 - lPos.x)*(flare4Coffset + 1.0) - (flare4Coffset*0.5))  *aspectRatio*flare4Cscale.x,  ((1.0 - lPos.y)*(flare4Coffset + 1.0) - (flare4Coffset*0.5))  *flare4Cscale.y);
		float flare4C = distance(flare4Cpos, vec2(texcoord.x*aspectRatio*flare4Cscale.x, texcoord.y*flare4Cscale.y));
              flare4C = 0.5 - flare4C;
              flare4C = clamp(flare4C*flare4Cfill, 0.0, 1.0)  ;
              flare4C = sin(flare4C*1.57075);
              flare4C *= sunmask;
              flare4C = pow(flare4C, 1.1);
              flare4C *= flare4Cpow;
              color.r += flare4C*1.6*flaremultR;
              color.g += flare4C*0.3*flaremultG;
              color.b += flare4C*1.1*flaremultB; 
	//far small pink flare4
		vec2 flare4Dscale = vec2(67.5*flarescale, 67.5*flarescale);
		float flare4Dpow = 1.0;
		float flare4Dfill = 2.0;
		float flare4Doffset = -0.35f;
		vec2 flare4Dpos = vec2(  ((1.0 - lPos.x)*(flare4Doffset + 1.0) - (flare4Doffset*0.5))  *aspectRatio*flare4Dscale.x,  ((1.0 - lPos.y)*(flare4Doffset + 1.0) - (flare4Doffset*0.5))  *flare4Dscale.y);
		float flare4D = distance(flare4Dpos, vec2(texcoord.x*aspectRatio*flare4Dscale.x, texcoord.y*flare4Dscale.y));
			  flare4D = 0.5 - flare4D;
              flare4D = clamp(flare4D*flare4Dfill, 0.0, 1.0)  ;
              flare4D = sin(flare4D*1.57075);
              flare4D *= sunmask;
              flare4D = pow(flare4D, 1.1);
              flare4D *= flare4Dpow;
			  color.r += flare4D*1.2*flaremultR;
			  color.g += flare4D*0.2*flaremultG;
			  color.b += flare4D*1.2*flaremultB;
	//far small pink flare5
		vec2 flare4Escale = vec2(60.5*flarescale, 60.5*flarescale);
		float flare4Epow = 1.0;
		float flare4Efill = 3.0;
		float flare4Eoffset = -0.3393f;
		vec2 flare4Epos = vec2(  ((1.0 - lPos.x)*(flare4Eoffset + 1.0) - (flare4Eoffset*0.5))  *aspectRatio*flare4Escale.x,  ((1.0 - lPos.y)*(flare4Eoffset + 1.0) - (flare4Eoffset*0.5))  *flare4Escale.y);
		float flare4E = distance(flare4Epos, vec2(texcoord.x*aspectRatio*flare4Escale.x, texcoord.y*flare4Escale.y));
              flare4E = 0.5 - flare4E;
              flare4E = clamp(flare4E*flare4Efill, 0.0, 1.0)  ;
              flare4E = sin(flare4E*1.57075);
              flare4E *= sunmask;
              flare4E = pow(flare4E, 1.1);
			  flare4E *= flare4Epow;
			  color.r += flare4E*1.2*flaremultR;
			  color.g += flare4E*0.2*flaremultG;
			  color.b += flare4E*1.0*flaremultB;
/* 	//Sun glow
		vec2 flare5scale = vec2(3.2*flarescale , 3.2*flarescale );
		float flare5pow = 13.4;
		float flare5fill = 1.0;
		float flare5offset = -2.0;
		vec2 flare5pos = vec2(  ((1.0 - lPos.x)*(flare5offset + 1.0) - (flare5offset*0.5))  *aspectRatio*flare5scale.x,  ((1.0 - lPos.y)*(flare5offset + 1.0) - (flare5offset*0.5))  *flare5scale.y);
		float flare5 = distance(flare5pos, vec2(texcoord.x*aspectRatio*flare5scale.x, texcoord.y*flare5scale.y));
              flare5 = 0.5 - flare5;
              flare5 = clamp(flare5*flare5fill, 0.0, 1.0)  ;
              flare5 *= sunmask;
              flare5 = pow(flare5, 1.9);
              flare5 *= flare5pow;
			  color.r += flare5*2.0*flaremultR;
			  color.g += flare5*0.4*flaremultG;
			  color.b += flare5*0.1*flaremultB;
*/  //Anamorphic lens
		vec2 flareEscale = vec2(0.2*flarescale, 5.0*flarescale);
		float flareEpow = 5.0;
		float flareEfill = 0.75;
		vec2 flareEpos = vec2(lPos.x*aspectRatio*flareEscale.x, lPos.y*flareEscale.y);
		float flareE = distance(flareEpos, vec2(texcoord.x*aspectRatio*flareEscale.x, texcoord.y*flareEscale.y));
			  flareE = 0.5 - flareE;
			  flareE = clamp(flareE*flareEfill, 0.0, 1.0)  ;
			  flareE *= sunmask;
			  flareE = pow(flareE, 1.4);
			  flareE *= flareEpow;
			  color.r += flareE*0.0*flaremultR;
			  color.g += flareE*0.05*flaremultG;
			  color.b += flareE*1.0*flaremultB;
	//first red sweep
		vec2 flare_extra3scale = vec2(32.0*flarescale, 32.0*flarescale);
		float flare_extra3pow = 2.5;
		float flare_extra3fill = 1.1;
		float flare_extra3offset = -1.3;
		vec2 flare_extra3pos = vec2(  ((1.0 - lPos.x)*(flare_extra3offset + 1.0) - (flare_extra3offset*0.5))  *aspectRatio*flare_extra3scale.x,  ((1.0 - lPos.y)*(flare_extra3offset + 1.0) - (flare_extra3offset*0.5))  *flare_extra3scale.y);
		float flare_extra3 = distance(flare_extra3pos, vec2(texcoord.x*aspectRatio*flare_extra3scale.x, texcoord.y*flare_extra3scale.y));
              flare_extra3 = 0.5 - flare_extra3;
              flare_extra3 = clamp(flare_extra3*flare_extra3fill, 0.0, 1.0)  ;
              flare_extra3 = sin(flare_extra3*1.57075);
              flare_extra3 *= sunmask;
              flare_extra3 = pow(flare_extra3, 1.1);
              flare_extra3 *= flare_extra3pow;
		//subtract
		vec2 flare_extra3Bscale = vec2(5.1*flarescale, 5.1*flarescale);
		float flare_extra3Bpow = 1.5;
		float flare_extra3Bfill = 1.0;
		float flare_extra3Boffset = -0.77f;
		vec2 flare_extra3Bpos = vec2(  ((1.0 - lPos.x)*(flare_extra3Boffset + 1.0) - (flare_extra3Boffset*0.5))  *aspectRatio*flare_extra3Bscale.x,  ((1.0 - lPos.y)*(flare_extra3Boffset + 1.0) - (flare_extra3Boffset*0.5))  *flare_extra3Bscale.y);
		float flare_extra3B = distance(flare_extra3Bpos, vec2(texcoord.x*aspectRatio*flare_extra3Bscale.x, texcoord.y*flare_extra3Bscale.y));
              flare_extra3B = 0.5 - flare_extra3B;
              flare_extra3B = clamp(flare_extra3B*flare_extra3Bfill, 0.0, 1.0)  ;
              flare_extra3B = sin(flare_extra3B*1.57075);
              flare_extra3B *= sunmask;
              flare_extra3B = pow(flare_extra3B, 0.9);
              flare_extra3B *= flare_extra3Bpow;
              flare_extra3 = clamp(flare_extra3 - flare_extra3B, 0.0, 10.0);
			  color.r += flare_extra3*1.0*flaremultR;
			  color.g += flare_extra3*0.0*flaremultG;
			  color.b += flare_extra3*0.2*flaremultB;
	//mid purple sweep
		vec2 flare_extra4scale = vec2(35.0*flarescale, 35.0*flarescale);
		float flare_extra4pow = 1.0;
		float flare_extra4fill = 1.1;
		float flare_extra4offset = -1.2;
		vec2 flare_extra4pos = vec2(  ((1.0 - lPos.x)*(flare_extra4offset + 1.0) - (flare_extra4offset*0.5))  *aspectRatio*flare_extra4scale.x,  ((1.0 - lPos.y)*(flare_extra4offset + 1.0) - (flare_extra4offset*0.5))  *flare_extra4scale.y);
		float flare_extra4 = distance(flare_extra4pos, vec2(texcoord.x*aspectRatio*flare_extra4scale.x, texcoord.y*flare_extra4scale.y));
              flare_extra4 = 0.5 - flare_extra4;
              flare_extra4 = clamp(flare_extra4*flare_extra4fill, 0.0, 1.0)  ;
              flare_extra4 = sin(flare_extra4*1.57075);
              flare_extra4 *= sunmask;
              flare_extra4 = pow(flare_extra4, 1.1);
              flare_extra4 *= flare_extra4pow;
		//subtract
		vec2 flare_extra4Bscale = vec2(5.1*flarescale, 5.1*flarescale);
		float flare_extra4Bpow = 1.5;
		float flare_extra4Bfill = 1.0;
		float flare_extra4Boffset = -0.77f;
		vec2 flare_extra4Bpos = vec2(  ((1.0 - lPos.x)*(flare_extra4Boffset + 1.0) - (flare_extra4Boffset*0.5))  *aspectRatio*flare_extra4Bscale.x,  ((1.0 - lPos.y)*(flare_extra4Boffset + 1.0) - (flare_extra4Boffset*0.5))  *flare_extra4Bscale.y);
		float flare_extra4B = distance(flare_extra4Bpos, vec2(texcoord.x*aspectRatio*flare_extra4Bscale.x, texcoord.y*flare_extra4Bscale.y));
			  flare_extra4B = 0.5 - flare_extra4B;
			  flare_extra4B = clamp(flare_extra4B*flare_extra4Bfill, 0.0, 1.0)  ;
			  flare_extra4B = sin(flare_extra4B*1.57075);
			  flare_extra4B *= sunmask;
			  flare_extra4B = pow(flare_extra4B, 0.9);
			  flare_extra4B *= flare_extra4Bpow;
			  flare_extra4 = clamp(flare_extra4 - flare_extra4B, 0.0, 10.0);
			  color.r += flare_extra4*0.7*flaremultR;
			  color.g += flare_extra4*0.1*flaremultG;
			  color.b += flare_extra4*1.0*flaremultB;
	//last blue/purple sweep
		vec2 flare_extra5scale = vec2(25.0*flarescale, 25.0*flarescale);
		float flare_extra5pow = 4.0;
		float flare_extra5fill = 1.1;
		float flare_extra5offset = -0.9;
		vec2 flare_extra5pos = vec2(  ((1.0 - lPos.x)*(flare_extra5offset + 1.0) - (flare_extra5offset*0.5))  *aspectRatio*flare_extra5scale.x,  ((1.0 - lPos.y)*(flare_extra5offset + 1.0) - (flare_extra5offset*0.5))  *flare_extra5scale.y);
		float flare_extra5 = distance(flare_extra5pos, vec2(texcoord.x*aspectRatio*flare_extra5scale.x, texcoord.y*flare_extra5scale.y));
              flare_extra5 = 0.5 - flare_extra5;
              flare_extra5 = clamp(flare_extra5*flare_extra5fill, 0.0, 1.0)  ;
              flare_extra5 = sin(flare_extra5*1.57075);
              flare_extra5 *= sunmask;
              flare_extra5 = pow(flare_extra5, 1.1);
              flare_extra5 *= flare_extra5pow;
		//subtract
		vec2 flare_extra5Bscale = vec2(5.1*flarescale, 5.1*flarescale);
		float flare_extra5Bpow = 1.0;
		float flare_extra5Bfill = 1.0;
		float flare_extra5Boffset = -0.77f;
		vec2 flare_extra5Bpos = vec2(  ((1.0 - lPos.x)*(flare_extra5Boffset + 1.0) - (flare_extra5Boffset*0.5))  *aspectRatio*flare_extra5Bscale.x,  ((1.0 - lPos.y)*(flare_extra5Boffset + 1.0) - (flare_extra5Boffset*0.5))  *flare_extra5Bscale.y);
		float flare_extra5B = distance(flare_extra5Bpos, vec2(texcoord.x*aspectRatio*flare_extra5Bscale.x, texcoord.y*flare_extra5Bscale.y));
			  flare_extra5B = 0.5 - flare_extra5B;
			  flare_extra5B = clamp(flare_extra5B*flare_extra5Bfill, 0.0, 1.0)  ;
			  flare_extra5B = sin(flare_extra5B*1.57075);
			  flare_extra5B *= sunmask;
			  flare_extra5B = pow(flare_extra5B, 0.9);
			  flare_extra5B *= flare_extra5Bpow;
			  flare_extra5 = clamp(flare_extra5 - flare_extra5B, 0.0, 10.0);
			  color.r += flare_extra5*0.2*flaremultR;
			  color.g += flare_extra5*0.1*flaremultG;
			  color.b += flare_extra5*0.6*flaremultB;
	//mid orange sweep
		vec2 flare10scale = vec2(6.0*flarescale, 6.0*flarescale);
		float flare10pow = 1.9;
		float flare10fill = 1.1;
		float flare10offset = -0.7;
		vec2 flare10pos = vec2(  ((1.0 - lPos.x)*(flare10offset + 1.0) - (flare10offset*0.5))  *aspectRatio*flare10scale.x,  ((1.0 - lPos.y)*(flare10offset + 1.0) - (flare10offset*0.5))  *flare10scale.y);
		float flare10 = distance(flare10pos, vec2(texcoord.x*aspectRatio*flare10scale.x, texcoord.y*flare10scale.y));
              flare10 = 0.5 - flare10;
              flare10 = clamp(flare10*flare10fill, 0.0, 1.0)  ;
              flare10 = sin(flare10*1.57075);
              flare10 *= sunmask;
              flare10 = pow(flare10, 1.1);
              flare10 *= flare10pow;
		//subtract
		vec2 flare10Bscale = vec2(5.1*flarescale, 5.1*flarescale);
		float flare10Bpow = 1.5;
		float flare10Bfill = 1.0;
		float flare10Boffset = -0.77f;
		vec2 flare10Bpos = vec2(  ((1.0 - lPos.x)*(flare10Boffset + 1.0) - (flare10Boffset*0.5))  *aspectRatio*flare10Bscale.x,  ((1.0 - lPos.y)*(flare10Boffset + 1.0) - (flare10Boffset*0.5))  *flare10Bscale.y);
		float flare10B = distance(flare10Bpos, vec2(texcoord.x*aspectRatio*flare10Bscale.x, texcoord.y*flare10Bscale.y));
              flare10B = 0.5 - flare10B;
              flare10B = clamp(flare10B*flare10Bfill, 0.0, 1.0)  ;
              flare10B = sin(flare10B*1.57075);
              flare10B *= sunmask;
              flare10B = pow(flare10B, 0.9);
              flare10B *= flare10Bpow;
              flare10 = clamp(flare10 - flare10B, 0.0, 10.0);
			  color.r += flare10*0.5*flaremultR;
			  color.g += flare10*0.3*flaremultG;
			  color.b += flare10*0.0*flaremultB;
	//mid blue sweep
		vec2 flare10Cscale = vec2(6.0*flarescale, 6.0*flarescale);
		float flare10Cpow = 1.9;
		float flare10Cfill = 1.1;
		float flare10Coffset = -0.6;
		vec2 flare10Cpos = vec2(  ((1.0 - lPos.x)*(flare10Coffset + 1.0) - (flare10Coffset*0.5))  *aspectRatio*flare10Cscale.x,  ((1.0 - lPos.y)*(flare10Coffset + 1.0) - (flare10Coffset*0.5))  *flare10Cscale.y);
		float flare10C = distance(flare10Cpos, vec2(texcoord.x*aspectRatio*flare10Cscale.x, texcoord.y*flare10Cscale.y));
              flare10C = 0.5 - flare10C;
              flare10C = clamp(flare10C*flare10Cfill, 0.0, 1.0)  ;
              flare10C = sin(flare10C*1.57075);
              flare10C *= sunmask;
              flare10C = pow(flare10C, 1.1);
              flare10C *= flare10Cpow;
		//subtract
		vec2 flare10Dscale = vec2(5.1*flarescale, 5.1*flarescale);
		float flare10Dpow = 1.5;
		float flare10Dfill = 1.0;
		float flare10Doffset = -0.67f;
		vec2 flare10Dpos = vec2(  ((1.0 - lPos.x)*(flare10Doffset + 1.0) - (flare10Doffset*0.5))  *aspectRatio*flare10Dscale.x,  ((1.0 - lPos.y)*(flare10Doffset + 1.0) - (flare10Doffset*0.5))  *flare10Dscale.y);
			float flare10D = distance(flare10Dpos, vec2(texcoord.x*aspectRatio*flare10Dscale.x, texcoord.y*flare10Dscale.y));
			  flare10D = 0.5 - flare10D;
			  flare10D = clamp(flare10D*flare10Dfill, 0.0, 1.0)  ;
			  flare10D = sin(flare10D*1.57075);
			  flare10D *= sunmask;
			  flare10D = pow(flare10D, 0.9);
			  flare10D *= flare10Dpow;
			  flare10C = clamp(flare10C - flare10D, 0.0, 10.0);
			  color.r += flare10C*0.5*flaremultR;
			  color.g += flare10C*0.3*flaremultG;
			  color.b += flare10C*1.0*flaremultB;
	//RedGlow1
        vec2 flare11scale = vec2(1.5*flarescale, 1.5*flarescale);
        float flare11pow = 1.1;
        float flare11fill = 2.0;
        float flare11offset = -0.523f;
        vec2 flare11pos = vec2(  ((1.0 - lPos.x)*(flare11offset + 1.0) - (flare11offset*0.5))  *aspectRatio*flare11scale.x,  ((1.0 - lPos.y)*(flare11offset + 1.0) - (flare11offset*0.5))  *flare11scale.y);
        float flare11 = distance(flare11pos, vec2(texcoord.x*aspectRatio*flare11scale.x, texcoord.y*flare11scale.y));
              flare11 = 0.5 - flare11;
              flare11 = clamp(flare11*flare11fill, 0.0, 1.0)  ;
              flare11 = pow(flare11, 2.9);
              flare11 *= sunmask;
              flare11 *= flare11pow;
              color.r += flare11*1.0*flaremultR;
              color.g += flare11*0.2*flaremultG;
              color.b += flare11*0.0*flaremultB;
	//PurpleGlow2
        vec2 flare12scale = vec2(2.5*flarescale, 2.5*flarescale);
        float flare12pow = 0.5;
        float flare12fill = 2.0;
        float flare12offset = -0.323f;
        vec2 flare12pos = vec2(  ((1.0 - lPos.x)*(flare12offset + 1.0) - (flare12offset*0.5))  *aspectRatio*flare12scale.x,  ((1.0 - lPos.y)*(flare12offset + 1.0) - (flare12offset*0.5))  *flare12scale.y);
        float flare12 = distance(flare12pos, vec2(texcoord.x*aspectRatio*flare12scale.x, texcoord.y*flare12scale.y));
              flare12 = 0.5 - flare12;
              flare12 = clamp(flare12*flare12fill, 0.0, 1.0)  ;
              flare12 = pow(flare12, 2.9);
              flare12 *= sunmask;
              flare12 *= flare12pow;
              color.r += flare12*0.7*flaremultR;
              color.g += flare12*0.0*flaremultG;
              color.b += flare12*1.0*flaremultB;
	//BlueGlow3
        vec2 flare13scale = vec2(1.0*flarescale, 1.0*flarescale);
        float flare13pow = 1.5;
        float flare13fill = 2.0;
        float flare13offset = +0.138f;
		vec2 flare13pos = vec2(  ((1.0 - lPos.x)*(flare13offset + 1.0) - (flare13offset*0.5))  *aspectRatio*flare13scale.x,  ((1.0 - lPos.y)*(flare13offset + 1.0) - (flare13offset*0.5))  *flare13scale.y);
        float flare13 = distance(flare13pos, vec2(texcoord.x*aspectRatio*flare13scale.x, texcoord.y*flare13scale.y));
              flare13 = 0.5 - flare13;
              flare13 = clamp(flare13*flare13fill, 0.0, 1.0)  ;
              flare13 = pow(flare13, 2.9);
              flare13 *= sunmask;
              flare13 *= flare13pow;
              color.r += flare13*0.0*flaremultR;
              color.g += flare13*0.2*flaremultG;
              color.b += flare13*1.0*flaremultB;
    return color;
}
#endif

#ifdef Volumetric_Lighting
vec3 funVL(vec3 fragpos){
	//float phase = 2.5+exp(dot(normalize(fragpos), normalize(shadowLightPosition))*3.0)/3.0;
	float VLrays = texture2DLod(colortex3, texcoord, 1).b * 0.175 * VL_amount;
	vec3 VLcolor = mix(funLightcolor(false), vec3(0.0), 1.0-exp(-length(fragpos)/(0.2*far-near))) * VLrays;
	if(isEyeInWater == 1.0) VLcolor = mix(vec3(0.05, 0.075, 0.110), vec3(0.0), 1.0-exp(-length(fragpos)/(0.2*far-near))) * VLrays;
	VLcolor *= (1.0-rainStrength);

	return VLcolor;
}
#endif

#ifdef Godrays
varying vec2 lightPos;
uniform vec3 upPosition;

vec3 funGodrays(vec3 fragpos) {
	vec3 normalFrag = normalize(fragpos);
	vec3 sunVec = normalize(sunPosition);
	float SdotU = dot(sunVec, normalize(upPosition));
	
	vec2 vis = clamp(vec2(SdotU, -SdotU) + 0.15, 0.0, 0.15) / 0.15;
	vis = pow(vis, vec2(4.0)); // vis.x sunVisibility, vis.y moonVisibility
    vec2 align = max(vec2(dot(normalFrag, sunVec), dot(normalFrag, -sunVec)), 0.0);
    vec2 decay = pow(align, vec2(30.0)) + pow(align, vec2(16.0)) * 0.8 + pow(align, vec2(2.0)) * 0.125;
	
    vec2 deltaTextCoord = (lightPos - texcoord) * 0.01;
    float gr = texture2DLod(colortex3, texcoord + deltaTextCoord, 1).g;
          gr += texture2DLod(colortex3, texcoord + 2.0 * deltaTextCoord, 1).g;
          gr += texture2DLod(colortex3, texcoord + 3.0 * deltaTextCoord, 1).g;
          gr += texture2DLod(colortex3, texcoord + 4.0 * deltaTextCoord, 1).g;
          gr += texture2DLod(colortex3, texcoord + 5.0 * deltaTextCoord, 1).g;
          gr += texture2DLod(colortex3, texcoord + 6.0 * deltaTextCoord, 1).g;
          gr += texture2DLod(colortex3, texcoord + 7.0 * deltaTextCoord, 1).g;

    vec2 finalWeights = (Godrays_Density * gr / 7.0) * decay * vis;

    vec3 grC = (funLightcolor(true) * finalWeights.x) + (vec3(0.025, 0.038, 0.055) * finalWeights.y);
	if(isEyeInWater == 1.0) grC = (vec3(0.05, 0.075, 0.110) * finalWeights.x) + (vec3(0.025, 0.038, 0.055) * finalWeights.y);
	grC *= (1.0-rainStrength);


    return grC * 0.5;
}
#endif

#ifdef Bloom 
//varying float eyeAdaptBloom;

vec3 funBloom(){
    const float weights[5] = float[](0.1974, 0.1746, 0.1209, 0.0617, 0.0219);
    const float offsets[5] = float[](0.0, 1.3846, 3.2307, 5.0769, 6.9230);
    vec2 newTC = texcoord.xy * 0.25;
    vec2 stepSize = vec2(0.0, 1.0 / viewHeight);

    vec3 blur = texture2D(colortex5, newTC).rgb * weights[0];
    for (int i = 1; i < 5; i++) {
        vec2 offs = stepSize * offsets[i];
        vec3 sample1 = texture2D(colortex5, newTC + offs).rgb;
        vec3 sample2 = texture2D(colortex5, newTC - offs).rgb;
        blur += (sample1 + sample2) * weights[i];
    }

    vec3 glow = blur * 12.0 * bloom_strength;
    vec3 overglow = glow * pow(length(glow) * 1.5, 2.0);
    vec3 finalBloom = (overglow + glow * 1.15) * (1.0 + (rainStrength * 2.0)) * 1.2;

    return finalBloom * 0.00001;
}
#endif

#ifdef Celshading
float utilDepth(vec2 coord) {
	return texture2D(depthtex0,coord).x;
}
float funCelshading() {
	//edge detect
	float dtresh = 1.0/(far-near) / (5000.0*Celradius);
	vec4 dc = vec4(utilDepth(texcoord.xy));
	vec3 border = vec3(1.0/viewWidth, 1.0/viewHeight, 0.0)*Celborder;
	vec4 sa = vec4(utilDepth(texcoord.xy + vec2(-border.x,-border.y)),
		 		   utilDepth(texcoord.xy + vec2(border.x,-border.y)),
		 		   utilDepth(texcoord.xy + vec2(-border.x,border.z)),
		 		   utilDepth(texcoord.xy + vec2(border.z,border.y)));
	//opposite side samples
	vec4 sb = vec4(utilDepth(texcoord.xy + vec2(border.x,border.y)),
		 		   utilDepth(texcoord.xy + vec2(-border.x,border.y)),
		 		   utilDepth(texcoord.xy + vec2(border.x,border.z)),
		 		   utilDepth(texcoord.xy + vec2(border.z,-border.y)));
	vec4 dd = abs(2.0* dc - sa - sb) - dtresh;
		 dd = step(dd.xyzw, vec4(0.0));

	return clamp(dot(dd,vec4(0.25f)),0.0,1.0);
}
#endif

#if !defined(IS_IRIS) && MC_VERSION < 11604
#ifdef Reflections
uniform sampler2D colortex2;
uniform ivec2 eyeBrightnessSmooth;
uniform mat4 gbufferProjection;

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
    //reflC.rgb = mix(skyC.rgb, reflC.rgb, reflC.a);
    return reflC;
}
#endif
#endif

void main() {

	vec4 albedo = texture2D(colortex4, texcoord.xy);
		 albedo.rgb = pow(albedo.rgb, vec3(2.2));
	float depth0 = texture2D(depthtex0, texcoord.xy).x;
	float depth1 = texture2D(depthtex1, texcoord.xy).x;

    float mats = texture2D(colortex1, texcoord.xy).b;
	bool isWater 		= mats > 0.79 && mats < 0.81;
	bool isIceglass 	= mats > 0.89 && mats < 0.91;
	
	#ifdef TAA
		vec3 fragpos0 = utilScreenSpace(vec3(gl_FragCoord.xy*texelSize-offsets[framemod8]*texelSize*0.5, texture2D(depthtex0, gl_FragCoord.xy*texelSize).x));
		vec3 fragpos1 = utilScreenSpace(vec3(gl_FragCoord.xy*texelSize-offsets[framemod8]*texelSize*0.5, texture2D(depthtex1, gl_FragCoord.xy*texelSize).x));
		float noise = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y + frameTimeCounter * 16.0);
	#else
		vec3 fragpos0 = utilScreenSpace(vec3(texcoord, depth0));
		vec3 fragpos1 = utilScreenSpace(vec3(texcoord, depth1));
		float noise = fract(gl_FragCoord.x * 0.618033988749895 + gl_FragCoord.y * 0.24412852441);
		//float noise = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y);	//dither
	#endif

	//sky
	if(depth0 >= 1.0) {

	//land
	} else {
	#ifdef Celshading
		albedo.rgb *= mix(funCelshading(), 1.0, 1.0-exp(-length(fragpos0)/(0.325*far-near)));
	#endif

	#if !defined(IS_IRIS) && MC_VERSION < 11604
	#ifdef Reflections
		#ifndef iceRefl
			isIceglass = false;
		#endif
	if ((isWater || isIceglass) && isEyeInWater < 0.9) {
		vec3 waternormal = texture2D(colortex2, texcoord.xy).xyz*2.0-1.0;

		float dither = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y);
			  dither = fract(dither + fract(frameTimeCounter * 128.0)) * 4.0;

		float F0 = isWater? 0.25 : 0.25 * (1.0 - albedo.a);
		vec3 reflectedVector = reflect(normalize(fragpos0), waternormal);
		float normalDotEye   = dot(waternormal, normalize(fragpos0));
		float fresnel        = pow(clamp(1.0 + normalDotEye, 0.0, 1.0), 4.0);
		fresnel              = mix(F0, 1.0, fresnel);

		vec2 skyMappingUV = utilVectorToSky(reflectedVector);
		vec3 skyrefl = texture2D(colortex6, skyMappingUV).rgb * 3.0;
		float skyLight = clamp((eyeBrightnessSmooth.y / 255.0 - 0.125) * 6.0, 0.0, 1.0);
		vec3 caveColor = vec3(0.01, 0.01, 0.015);
		skyrefl = mix(caveColor, skyrefl, skyLight);
		vec4 reflection = funReflections(reflectedVector, fragpos0, dither, fresnel, skyrefl.rgb);

	#ifndef waterRefl
		if(isWater)reflection = vec4(0.0);
	#endif
		if(isIceglass) { skyrefl = albedo.rgb * 1.5; albedo.rgb *= 1.5; }
		if(isWater) albedo.b *= 0.66;
		albedo.rgb = mix(albedo.rgb, mix(skyrefl.rgb, reflection.rgb, reflection.a), fresnel);
	}
	#endif
	#endif

	#ifdef Whiteworld
		if(depth0 < 1.0 && depth1 == depth0)albedo.rgb += vec3(0.5);
	#endif
	}

	#ifdef Fog
		if(isEyeInWater == 1.0) albedo.rgb = funFog(albedo.rgb, fragpos1);	//apply fog again for horzion line underwater, before other effects
	#endif

	#ifdef Bloom
		albedo.rgb += funBloom();
	#endif

	#ifdef Godrays
		albedo.rgb += funGodrays(fragpos0);
	#endif

	#ifdef Volumetric_Lighting
		albedo.rgb += funVL(fragpos1);
	#endif

	#ifdef Lens_Flares
		albedo.rgb = funLensFlare(albedo.rgb);
	#endif

	#ifdef Fog
	//recreate effect fog for any MC version
	if(darknessFactor > 0.01 || blindness > 0.01){
		float dist = length(fragpos1);
		float effectEnd = (blindness > 0.01) ? 5.0 : 15.0;
		albedo.rgb = mix(albedo.rgb, vec3(0.0001), clamp(dist / effectEnd, 0.0, 1.0));
		if (blindness <= 0.01) albedo.rgb *= mix(1.0, 0.015, darknessLightFactor);	//pulsate brightness during darkness effect
	}
	#endif

	//albedo.rgb = (albedo.rgb / 50.0 * pow(eyeAdapt,0.88)); // / 50 here and * 50 in final was in the og
	//albedo.rgb = (albedo.rgb * pow(eyeAdapt,0.88));
	albedo.rgb = pow(albedo.rgb, vec3(0.454));

	gl_FragData[0] = albedo;
}