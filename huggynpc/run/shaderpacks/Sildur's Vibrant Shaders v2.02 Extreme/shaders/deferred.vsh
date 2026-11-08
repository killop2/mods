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


#define lightingColors
#include "shaders.settings"

varying vec2 texcoord;

varying vec3 sunVec;
varying vec3 upVec;
varying vec3 sky1;
varying vec3 sky2;
varying vec3 nsunlight;
varying vec3 sunlight;
varying vec3 rawAvg;
varying vec3 cloudColor;
varying vec3 cloudColor2;

varying float tr2;
varying float eyeAdapt;
varying float SdotU;
varying float sunVisibility;
varying float moonVisibility;

uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform int worldTime;
uniform float rainStrength;
uniform ivec2 eyeBrightnessSmooth;

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
													
						
float luma(vec3 color) {
	return dot(color,vec3(0.299, 0.587, 0.114));
}

void main() {

	//Positioning
	gl_Position = ftransform();
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	/*--------------------------------*/

	//Sun/Moon position
	sunVec = normalize(sunPosition);
	upVec = normalize(upPosition);
	
	SdotU = dot(sunVec,upVec);
	sunVisibility = pow(clamp(SdotU+0.15,0.0,0.15)/0.15,4.0);
	moonVisibility = pow(clamp(-SdotU+0.15,0.0,0.15)/0.15,4.0);
	/*--------------------------------*/
	
	//reduced the sun color to a 7 array
	float hour = max(mod(float(worldTime)/1000.0+2.0,24.0)-2.0,0.0);  //-0.1
	float cmpH = max(-abs(floor(hour)-6.0)+6.0,0.0); //12
	float cmpH1 = max(-abs(floor(hour)-5.0)+6.0,0.0); //1
	
	vec3 temp = ToD[int(cmpH)];
	vec3 temp2 = ToD[int(cmpH1)];

	sunlight = mix(temp,temp2,fract(hour));
	sunlight.rgb += vec3(r_multiplier,g_multiplier,b_multiplier);	//allows lighting colors to be tweaked.
	sunlight.rgb *= light_brightness;								//brightness needs to be adjusted if we tweak lighting colors.
	
	vec3 sunlight04 = pow(sunlight,vec3(0.454));
	/*-----------------------------------------------------------------*/
	
	//Lighting
	float eyebright = max(float(eyeBrightnessSmooth.y)/255.0-0.5/16.0,0.0)*1.03225806452;
	float SkyL2 = mix(1.0,eyebright*eyebright,eyebright);

	vec2 trCalc = min(abs(float(worldTime)-vec2(23050.0,12700.0)),750.0);	//23000 in compo0 version
	tr2 = max(min(trCalc.x,trCalc.y)/375.0-1.0,0.0);
	float tr = clamp(min(min(distance(float(worldTime),23050.0),750.0),min(distance(float(worldTime),12700.0),800.0))/800.0-0.5,0.0,1.0)*2.0;
//in comp0
//float tr2 = clamp(min(min(distance(float(worldTime),23000.0),750.0),min(distance(float(worldTime),12700.0),800.0))/800.0-0.5,0.0,1.0)*2.0;

	vec4 bounced = vec4(0.5,0.66,1.3,0.27);
	vec3 sun_ambient = (bounced.w * (1.0+rainStrength*7.0)) * (vec3(0.25,0.62,1.32)-rainStrength*vec3(0.1,0.47,1.17)) + (sunlight*(bounced.x + bounced.z))*(1.0-rainStrength*0.95);

	const vec3 moonlight = vec3(0.0016, 0.00288, 0.00448);
	vec3 moon_ambient = moonlight * (1.0 + eyebright*eyebright*eyebright);

	vec4 bounced2 = vec4(0.5*SkyL2,0.66*SkyL2,0.7,0.3);

	vec3 sun_ambient2 = bounced2.w * (vec3(0.25,0.62,1.32)-rainStrength*vec3(0.11,0.32,1.07)) + sunlight*(bounced2.x + bounced2.z);
	vec3 moon_ambient2 = (moonlight*3.5);

	const vec3 moonlightX8 = vec3(0.0128, 0.02304, 0.03584);
	rawAvg = (sun_ambient*sunVisibility + moonlightX8*moonVisibility)*(0.05+tr*0.15)*4.7+0.0002;	
	
	vec3 avgAmbient =(sun_ambient2*sunVisibility + moon_ambient2*moonVisibility)*eyebright*eyebright*(0.05+tr2*0.15)*4.7+0.0006;

	eyeAdapt = log(clamp(luma(avgAmbient),0.007,80.0)) * 0.36629598;
	eyeAdapt = 1.0/pow(eyeLight,eyeAdapt)*1.75;
	/*--------------------------------*/

	//Sky lighting
	float mcosS = max(SdotU,0.0);				
	float skyMult = max(SdotU*0.1+0.1,0.0)/0.2*(1.0-rainStrength*0.6)*0.7;
	
	float rainSunFactor = sunVisibility * (1.0 - rainStrength * 0.95);
	nsunlight = normalize(pow(mix(sunlight04 ,(5.0 * rainSunFactor) * sunlight04 + vec3(0.3,0.3,0.35),rainStrength),vec3(2.2)))*0.6*skyMult;
	
	//comp1:
	//vec3 sky_color = vec3(0.15, 0.4, 1.0);
	vec3 sky_color = vec3(0.05, 0.32, 1.0);
	
	sky_color = normalize(mix(sky_color,(2.0 * rainSunFactor) * sunlight04 + vec3(0.3,0.3,0.3)*length(sunlight04 ),rainStrength)); //normalize colors in order to don't change luminance
	
	sky1 = sky_color*0.6*skyMult;
	sky2 = mix(sky_color,mix(nsunlight,sky_color,rainStrength*0.9),1.0-max(mcosS-0.2,0.0)*0.5)*0.6*skyMult;
	
	vec3 rainCloudC = vec3(0.65, 0.65, 0.95) * 0.25; //og was without *0.25 and all 0.65
	float lenRawAvg = length(rawAvg);
	cloudColor = (sunlight04 * sunVisibility * lenRawAvg + 2.0 * moonlight * moonVisibility) * (1.0 - rainStrength * 0.17) + rawAvg * (0.7 * (1.0 - rainStrength * 0.1));
	cloudColor2 = (0.5 * sunlight * sunVisibility * lenRawAvg + 2.0 * moonlight * moonVisibility) * (1.0 - rainStrength * 0.15) + (1.5 * lenRawAvg * (1.0 - rainStrength * 0.1)) * mix(vec3(0.15, 0.4, 1.0),rainCloudC,rainStrength);
	//0.1*sunlight
}