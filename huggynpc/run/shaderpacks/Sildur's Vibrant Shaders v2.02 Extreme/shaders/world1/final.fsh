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


#define final
#define AA_settings
#include "/shaders.settings"

varying vec2 texcoord;

uniform sampler2D colortex4;	//final image
uniform sampler2D colortex7;	//TAA

vec4 utilTexture(vec2 coord){
	#ifdef TAA
		return texture2D(colortex7, coord);
	#else
		return texture2D(colortex4, coord);
	#endif
}

#if Showbuffer > 0
uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D colortex5;
uniform sampler2D colortex6;
#endif
uniform int isEyeInWater;

uniform float aspectRatio;
uniform float viewWidth;
uniform float viewHeight;
uniform float rainStrength;
uniform float frameTimeCounter;

#if defined Depth_of_Field || defined Motionblur
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;
uniform sampler2D depthtex2;
#endif

#ifdef Motionblur
uniform vec3 cameraPosition; 
uniform vec3 previousCameraPosition;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferPreviousProjection;
uniform mat4 gbufferPreviousModelView;
uniform mat4 gbufferModelViewInverse;

vec3 funMotionblur(vec3 albedo, float depth){
	vec4 currentPosition = vec4(texcoord, depth, 1.0)*2.0-1.0;
	
	vec4 fragposition = gbufferProjectionInverse * currentPosition;
		 fragposition = gbufferModelViewInverse * fragposition;
		 fragposition /= fragposition.w;
		 fragposition.xyz += cameraPosition;
	
	vec4 previousPosition = fragposition;
		 previousPosition.xyz -= previousCameraPosition;
		 previousPosition = gbufferPreviousModelView * previousPosition;
		 previousPosition = gbufferPreviousProjection * previousPosition;
		 previousPosition /= previousPosition.w;

	vec2 velocity = (currentPosition - previousPosition).xy * MB_strength;
	vec2 coord = texcoord.xy + velocity;

	int mb = 1;
	for (int i = 0; i < 15; ++i, coord += velocity) {
		if (coord.s > 1.0 || coord.t > 1.0 || coord.s < 0.0 || coord.t < 0.0) break;
			albedo.rgb += utilTexture(coord).xyz;
		++mb;
	}
	return albedo.rgb / mb;
}
#endif

#ifdef Depth_of_Field
uniform float near;
uniform float far;

float utilLinearD(float depth) {
    return (2.0 * near) / (far + near - depth * (far - near));
}

//hexagon pattern
const vec2 hex_offsets[60] = vec2[60] (	vec2(  0.2165,  0.1250 ),
											vec2(  0.0000,  0.2500 ),
											vec2( -0.2165,  0.1250 ),
											vec2( -0.2165, -0.1250 ),
											vec2( -0.0000, -0.2500 ),
											vec2(  0.2165, -0.1250 ),
											vec2(  0.4330,  0.2500 ),
											vec2(  0.0000,  0.5000 ),
											vec2( -0.4330,  0.2500 ),
											vec2( -0.4330, -0.2500 ),
											vec2( -0.0000, -0.5000 ),
											vec2(  0.4330, -0.2500 ),
											vec2(  0.6495,  0.3750 ),
											vec2(  0.0000,  0.7500 ),
											vec2( -0.6495,  0.3750 ),
											vec2( -0.6495, -0.3750 ),
											vec2( -0.0000, -0.7500 ),
											vec2(  0.6495, -0.3750 ),
											vec2(  0.8660,  0.5000 ),
											vec2(  0.0000,  1.0000 ),
											vec2( -0.8660,  0.5000 ),
											vec2( -0.8660, -0.5000 ),
											vec2( -0.0000, -1.0000 ),
											vec2(  0.8660, -0.5000 ),
											vec2(  0.2163,  0.3754 ),
											vec2( -0.2170,  0.3750 ),
											vec2( -0.4333, -0.0004 ),
											vec2( -0.2163, -0.3754 ),
											vec2(  0.2170, -0.3750 ),
											vec2(  0.4333,  0.0004 ),
											vec2(  0.4328,  0.5004 ),
											vec2( -0.2170,  0.6250 ),
											vec2( -0.6498,  0.1246 ),
											vec2( -0.4328, -0.5004 ),
											vec2(  0.2170, -0.6250 ),
											vec2(  0.6498, -0.1246 ),
											vec2(  0.6493,  0.6254 ),
											vec2( -0.2170,  0.8750 ),
											vec2( -0.8663,  0.2496 ),
											vec2( -0.6493, -0.6254 ),
											vec2(  0.2170, -0.8750 ),
											vec2(  0.8663, -0.2496 ),
											vec2(  0.2160,  0.6259 ),
											vec2( -0.4340,  0.5000 ),
											vec2( -0.6500, -0.1259 ),
											vec2( -0.2160, -0.6259 ),
											vec2(  0.4340, -0.5000 ),
											vec2(  0.6500,  0.1259 ),
											vec2(  0.4325,  0.7509 ),
											vec2( -0.4340,  0.7500 ),
											vec2( -0.8665, -0.0009 ),
											vec2( -0.4325, -0.7509 ),
											vec2(  0.4340, -0.7500 ),
											vec2(  0.8665,  0.0009 ),
											vec2(  0.2158,  0.8763 ),
											vec2( -0.6510,  0.6250 ),
											vec2( -0.8668, -0.2513 ),
											vec2( -0.2158, -0.8763 ),
											vec2(  0.6510, -0.6250 ),
											vec2(  0.8668,  0.2513 ));
//Dof constant values
const float focal = 0.024;
float aperture = 0.008;	
const float sizemult = DoF_Strength;
uniform float centerDepthSmooth; 
const float centerDepthHalflife = 2.0f; 

vec3 funDoF(vec3 albedo, float depth0, float depth1) {
	float pw = 1.0 / viewWidth;
	float z = utilLinearD(depth0) * far;
	#ifdef smoothDof
		float focus = utilLinearD(centerDepthSmooth)*far;
	#else
		float focus = utilLinearD(texture2D(depthtex0, vec2(0.5)).r)*far;
	#endif
	float pcoc = min(abs(aperture * (focal * (z - focus)) / (z * (focus - focal)))*sizemult,pw*15.0);	
	#ifdef Distance_Blur
		float getdist = 1-(exp(-pow(utilLinearD(depth1)/Dof_Distance_View*far,4.0-(2.7*rainStrength))*4.0));	
		pcoc = min(getdist*pw*20.0,pw*20.0);
	#endif
	for ( int i = 0; i < 60; i++) {
		albedo.rgb += utilTexture(texcoord.xy + hex_offsets[i]*pcoc*vec2(1.0,aspectRatio)).rgb;
	}
	return albedo.rgb / 61.0;
}
#endif
/*
#ifdef Rain_Drops
varying vec2 rainPos1;
varying vec2 rainPos2;
varying vec2 rainPos3;
varying vec2 rainPos4;
varying vec4 weights;

float funRainLens() {
	vec2 ratio = vec2(aspectRatio, 1.0);
	vec2 rainCoord = texcoord.xy * ratio;

	float dist1 = distance(rainPos1 * ratio, rainCoord) / 0.1;
	float drop1 = exp(-dist1 * dist1);
		
	float dist2 = distance(rainPos2 * ratio, rainCoord) / 0.07;
	float drop2 = exp(-dist2 * dist2);
		
	float dist3 = distance(rainPos3 * ratio, rainCoord) / 0.086;
	float drop3 = exp(-dist3 * dist3);
		
	float dist4 = distance(rainPos4 * ratio, rainCoord) / 0.092;
	float drop4 = exp(-dist4 * dist4);

	float rainlens = drop1 * weights.x;
		  rainlens += drop2 * weights.y;
		  rainlens += drop3 * weights.z;
		  rainlens += drop4 * weights.w;

	return rainlens;
}
#endif
*/
#if Showbuffer == 2
vec3 decode (vec2 enc){
    vec2 fenc = enc*4.0-2.0;
    float f = dot(fenc,fenc);
    float g = sqrt(1.0-f/4.0);
    vec3 n;
    n.xy = fenc*g;
    n.z = 1.0-f/2.0;
    return n;
}
#endif

vec3 Uncharted2Tonemap(vec3 x) {
	x*= Brightness;
	float A = 0.28;
	float B = 0.29;		
	float C = 0.10;
	float D = 0.2;
	float E = 0.025;
	float F = 0.35;
	return ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
}

void main() {

#if defined Depth_of_Field || defined Motionblur
	float depth0 = texture2D(depthtex0, texcoord).x;
	float depth1 = texture2D(depthtex1, texcoord).x;
	float depth2 = texture2D(depthtex2, texcoord).x;
	bool isHand = (depth2 > depth1);
#endif
	
	float rainlens = 0.0;
	vec2 fake_refract = vec2(0.0);
/*
#ifdef Rain_Drops
	if (rainStrength > 0.02) rainlens = funRainLens();
#endif	
*/
#ifdef Refraction
	fake_refract = vec2(sin(frameTimeCounter + texcoord.x*100.0 + texcoord.y*50.0),cos(frameTimeCounter + texcoord.y*100.0 + texcoord.x*50.0));
#endif

	vec2 newTC = clamp(texcoord + fake_refract * 0.01 * (rainlens+isEyeInWater*0.2), 1.0/vec2(viewWidth,viewHeight), 1.0-1.0/vec2(viewWidth,viewHeight));
	vec4 albedo = utilTexture(newTC.xy);

#ifdef Depth_of_Field
	if(!isHand) albedo.rgb = funDoF(albedo.rgb, depth0, depth1);
#endif
	
#ifdef Motionblur
	if(!isHand) albedo.rgb = funMotionblur(albedo.rgb, depth0);
#endif

	albedo.rgb = pow(albedo.rgb, vec3(2.2)); //this was the og saturation boost in composite1.fsh
	vec3 curr = Uncharted2Tonemap(albedo.rgb*4.7);
	albedo.rgb = pow(curr/Uncharted2Tonemap(vec3(15.2)),vec3(1.0/Contrast));

#if Showbuffer == 1
	albedo.rgb = texture2D(colortex1, texcoord).rgb;			//lightmap, mats
#endif
#if Showbuffer == 2
	albedo.rgb = decode(texture2D(colortex2, texcoord).xy);		//normals, PCSS, gbuffers_textured only
#endif
#if Showbuffer == 3
	albedo.rgb = texture2D(colortex3, texcoord).rgb;			//empty, godrays, vl
#endif
#if Showbuffer == 4	
	albedo.rgb = texture2D(colortex4, texcoord.xy).rgb;			//albedo deferred+translucent
#endif	
#if Showbuffer == 5	
	albedo.rgb = texture2D(colortex5, texcoord.xy*0.25).rgb;	//bloom
#endif	
#if Showbuffer == 6	
	albedo.rgb = texture2D(colortex6, texcoord.xy).rgb;			//panorama sky	
#endif	
#if Showbuffer == 7	
	albedo.rgb = texture2D(colortex7, texcoord.xy).rgb;			//TAA
#endif

	gl_FragColor = albedo;
}
