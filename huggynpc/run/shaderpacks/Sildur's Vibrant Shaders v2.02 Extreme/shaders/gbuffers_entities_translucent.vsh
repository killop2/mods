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
#define AA_settings
#define lightingColors
#include "shaders.settings"

varying vec4 color;
varying vec2 texcoord;
varying vec3 LightC;
varying vec3 ambientC;
varying vec3 normal;
varying float skyL;
varying float SkyL2;
varying float dist;

uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 cameraPosition;
uniform int isEyeInWater;
uniform int worldTime;
uniform float rainStrength;
						
uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;

#ifdef HandLight
uniform int heldBlockLightValue;
uniform int heldBlockLightValue2;
#endif
uniform float nightVision;

#ifdef TAA
uniform float viewWidth;
uniform float viewHeight;
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
uniform vec3 shadowLightPosition;
uniform ivec2 eyeBrightnessSmooth;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
varying vec3 shadowData;
varying vec3 vertexShadowPos;

#define diagonal3(mat) vec3((mat)[0].x, (mat)[1].y, (mat)[2].z)
vec3 funShadows(vec3 shadowPos, vec3 normal) {
	//float diffuse = (translucent)? dot(normal, normalize(shadowLightPosition)) * 0.35 + 0.65 : clamp(dot(normal, normalize(shadowLightPosition)),0.0,1.0); //translucent = 0.75 before, * 0.35 + 0.65 must always be 1 total
	float diffuse = clamp(dot(normal, normalize(shadowLightPosition)),0.0,1.0);
	vec3 finalShading = vec3(diffuse);

	if (shadowPos.z > -0.38) shadowPos.z -= 0.38; 
	shadowData = vec3(diffuse, 0.0, 0.0);

	if (diffuse > 0.001) {
		shadowPos = mat3(shadowModelView) * shadowPos + shadowModelView[3].xyz;
		shadowPos = diagonal3(shadowProjection) * shadowPos + shadowProjection[3].xyz;

		float distortion = utilDistortion(shadowPos.xy);
		shadowPos.xy *= distortion;

		if (abs(shadowPos.x) < 1.0-1.5/shadowMapResolution && abs(shadowPos.y) < 1.0-1.5/shadowMapResolution && abs(shadowPos.z) < 6.0){  //only if on shadowmap
			float pdepth = 2.0;	//fallback if PCSS shadows are disabled, default is 1.412
			const float threshMul = max(2048.0/shadowMapResolution*shadowDistance*0.0078125,0.95);
			float distortThresh = (sqrt(1.0-diffuse*diffuse)/diffuse+0.7)/distortion;
			shadowPos = shadowPos * vec3(0.5,0.5,0.08333333) + vec3(0.5,0.5,0.5);

			float rdMul = pdepth*distortion*Nearshadowplane*k/shadowMapResolution;
			float bias = distortThresh*0.0001666667*threshMul;

			shadowData = vec3(diffuse, rdMul, bias);
		}
	}
	return shadowPos;
}
#endif

/*
//composite1 version
const float redtint = 1.5;
const vec3 ToD[7] = vec3[7](  vec3(redtint,0.15,0.02),
								vec3(redtint,0.35,0.09),
								vec3(redtint,0.5,0.26),
								vec3(redtint,0.5,0.35),
								vec3(redtint,0.5,0.36),
								vec3(redtint,0.5,0.37),
								vec3(redtint,0.5,0.38));
*/

//composite0 version
const vec3 ToD[7] = vec3[7](  vec3(0.58597,0.16,0.005),
								vec3(0.58597,0.31,0.08),
								vec3(0.58597,0.45,0.16),
								vec3(0.58597,0.5,0.35),
								vec3(0.58597,0.5,0.36),
								vec3(0.58597,0.5,0.37),
								vec3(0.58597,0.5,0.38));

void main() {

    vec3 viewPos = (gl_ModelViewMatrix * gl_Vertex).xyz;

	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	vec2 lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	
	vec3 position = mat3(gbufferModelViewInverse) * viewPos + gbufferModelViewInverse[3].xyz;

	normal = normalize(gl_NormalMatrix * gl_Normal);	 

	//Sun/Moon position
	vec3 sunVec = normalize(sunPosition);
	vec3 upVec = normalize(upPosition);
	
	float SdotU = dot(sunVec,upVec);
	float sunVisibility = pow(clamp(SdotU+0.15,0.0,0.15)/0.15,4.0);
	float moonVisibility = pow(clamp(-SdotU+0.15,0.0,0.15)/0.15,4.0);
	/*--------------------------------*/
	
	//reduced the sun color to a 7 array
	float hour = max(mod(float(worldTime)/1000.0+2.0,24.0)-2.0,0.0);  //-0.1
	float cmpH = max(-abs(floor(hour)-6.0)+6.0,0.0); //12
	float cmpH1 = max(-abs(floor(hour)-5.0)+6.0,0.0); //1
	
	vec3 temp = ToD[int(cmpH)];
	vec3 temp2 = ToD[int(cmpH1)];

	vec3 sunlight = mix(temp,temp2,fract(hour));
		 sunlight.rgb += vec3(r_multiplier,g_multiplier,b_multiplier);	//allows lighting colors to be tweaked.
		 sunlight.rgb *= light_brightness;								//brightness needs to be adjusted if we tweak lighting colors.
	
	float tr = clamp(min(min(distance(float(worldTime),23050.0),750.0),min(distance(float(worldTime),12700.0),800.0))/800.0-0.5,0.0,1.0)*2.0;
	/*-----------------------------------------------------------------*/
	
	color = gl_Color;

	//Lighting and colors
	float entityBoost = 4.0;
	vec2 lightmap = lmcoord;

	#ifdef HandLight
	bool underwaterlava = (isEyeInWater == 1.0 || isEyeInWater == 2.0);
	if(!underwaterlava) lightmap.x = max(lightmap.x, max(max(float(heldBlockLightValue), float(heldBlockLightValue2)) - 1.0 - length(position.xyz), 0.0) / 15.0); //was fragpos1
	#endif
	float torch_lightmap = 16.0-min(15.0,(lightmap.x-0.03125)*17.066666667);
	float fallof1 = clamp(1.0 - pow(torch_lightmap*0.0625,4.0),0.0,1.0);
	torch_lightmap = fallof1*fallof1/(torch_lightmap*torch_lightmap+1.0);
	vec3 emissiveLightC = vec3(emissive_R,emissive_G,emissive_B) * entityBoost;
	
	float NdotL = dot(normal, sunVec);
	float NdotU = dot(normal, upVec);
	
	vec3 moonlight = vec3(0.5, 0.9, 1.8) * Moonlight * entityBoost;  //use same colors as deferred

	vec2 visibility = vec2(sunVisibility,moonVisibility);

	skyL = max(lightmap.y-0.125,0.0)*1.14285714286;	
	SkyL2 = skyL*skyL;
	float skyc2 = mix(1.0,SkyL2,skyL);

	vec4 bounced = vec4(NdotL,NdotL,NdotL,NdotU) * vec4(-0.14*skyL*skyL,0.33,0.7,0.1) + vec4(0.6,0.66,0.7,0.25);
		 bounced *= vec4(skyc2,skyc2,visibility.x-tr*visibility.x,0.8);

	float weatherFactor = 1.0 - rainStrength * 0.99;
	vec3 sun_ambient = bounced.w * (vec3(0.24, 1.2, 2.64)+rainStrength*vec3(0.115,-0.759,-2.07))+ (1.6*weatherFactor)*sunlight*(sqrt(bounced.w)*bounced.x*2.4 + bounced.z);
	vec3 moon_ambient = (moonlight*0.7 + moonlight*bounced.y)*4.0;

	//vec3 LightC = mix(sunlight,moonlight,moonVisibility)*tr*(1.0-rainStrength*0.99);
	LightC = mix(sunlight,moonlight,moonVisibility)*weatherFactor; //remove time check to smooth out day night transition
	vec3 amb1 = (sun_ambient*visibility.x + moon_ambient*visibility.y)*SkyL2*(0.0195+tr*0.1105);
	float finalminlight = (nightVision > 0.01)? 0.15 : minlight; //add nightvision support but make sure minlight is still adjustable.	
	ambientC = amb1 + emissiveLightC*torch_lightmap*0.66 + (finalminlight*min(skyL+0.375,0.5625))*normalize(amb1+0.0001);
	ambientC = max(vec3(0.0), ambientC);	//prevent negative values to fix NaNs on janky drivers.
	/*----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------*/

	dist = length(viewPos);

	gl_Position = gl_ProjectionMatrix * gbufferModelView * vec4(position, 1.0);
	#ifdef TAA
		gl_Position.xy += offsets[framemod8] * gl_Position.w*texelSize;
	#endif

	#ifdef Shadows
		vertexShadowPos = funShadows(position, normal);
	#endif
}