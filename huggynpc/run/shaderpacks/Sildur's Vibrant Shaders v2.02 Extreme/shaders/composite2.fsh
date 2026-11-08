#version 120
/* DRAWBUFFERS:7 */
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
#include "shaders.settings"

varying vec2 texcoord;
uniform sampler2D colortex4;	//finished deferred + translucent

uniform float viewWidth;
uniform float viewHeight;

vec2 texelSize = vec2(1.0/viewWidth,1.0/viewHeight);

#ifdef TAA
const bool colortex7Clear = false;
uniform sampler2D colortex1;	// lightmap, mats
uniform sampler2D colortex7;
uniform sampler2D depthtex0;
uniform sampler2D depthtex2;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferPreviousProjection;
uniform mat4 gbufferPreviousModelView;
uniform mat4 gbufferProjectionInverse;
uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;

#define diagonal3(m) vec3((m)[0].x, (m)[1].y, (m)[2].z)
#define  projMAD(m, v) (diagonal3(m) * (v) + (m)[3].xyz)

#define BLEND_FACTOR 0.1 			//[0.01 0.02 0.03 0.04 0.05 0.06 0.08 0.1 0.12 0.14 0.16] higher values = more flickering but sharper image, lower values = less flickering but the image will be blurrier
#define MOTION_REJECTION 1.0		//[0.0 0.05 0.1 0.15 0.2 0.25 0.3 0.35 0.4 0.5] //Higher values=sharper image in motion at the cost of flickering
#define ANTI_GHOSTING 0.0			//[0.0 0.25 0.5 0.75 1.0] High values reduce ghosting but may create flickering
#define FLICKER_REDUCTION 1.0		//[0.0 0.25 0.5 0.75 1.0] High values reduce flickering but may reduce sharpness

#define RGB_TO_YCOCG(c) vec3(dot(c, vec3(0.25, 0.5, 0.25)), dot(c, vec3(0.5, 0.0, -0.5)), dot(c, vec3(-0.25, 0.5, -0.25)))
#define YCOCG_TO_RGB(c) vec3(c.r + c.g - c.b, c.r + c.b, c.r - c.g - c.b)

vec3 utilScreenSpace(vec3 pos) {
	vec4 iProjDiag = vec4(gbufferProjectionInverse[0].x, gbufferProjectionInverse[1].y, gbufferProjectionInverse[2].zw);
	vec3 p3 = pos * 2.0 - 1.0;
    vec4 fragposition = iProjDiag * p3.xyzz + gbufferProjectionInverse[3];
    return fragposition.xyz / fragposition.w;
}

vec3 funTAA(bool sky){
	float mats = texture2D(colortex1, texcoord.xy).b;
	bool noSharpen = mats > 0.39 && mats < 0.61 || mats > 0.79 && mats < 0.91; //skip translucent, mats > 0.69 && mats < 0.71

	vec2 du = vec2(texelSize.x*2.0, 0.0);
	vec2 dv = vec2(0.0, texelSize.y*2.0);

	vec3 dtl = vec3(texcoord,0.0) + vec3(-texelSize, texture2D(depthtex2, texcoord - dv - du).x);
	vec3 dtr = vec3(texcoord,0.0) + vec3( texelSize.x, -texelSize.y, texture2D(depthtex2, texcoord - dv + du).x);
	vec3 dmc = vec3(texcoord,0.0) + vec3( 0.0, 0.0, texture2D(depthtex2, texcoord).x);
	vec3 dbl = vec3(texcoord,0.0) + vec3(-texelSize.x, texelSize.y, texture2D(depthtex2, texcoord + dv - du).x);
	vec3 dbr = vec3(texcoord,0.0) + vec3( texelSize.x, texelSize.y, texture2D(depthtex2, texcoord + dv + du).x);

	vec3 closestToCamera = dmc;
		 closestToCamera = closestToCamera.z > dtr.z? dtr : closestToCamera;
		 closestToCamera = closestToCamera.z > dtl.z? dtl : closestToCamera;
		 closestToCamera = closestToCamera.z > dbl.z? dbl : closestToCamera;
		 closestToCamera = closestToCamera.z > dbr.z? dbr : closestToCamera;

	vec3 fragposition = utilScreenSpace(closestToCamera);
		 fragposition = mat3(gbufferModelViewInverse) * fragposition + gbufferModelViewInverse[3].xyz + (cameraPosition - previousCameraPosition);
	vec3 previousPosition = mat3(gbufferPreviousModelView) * fragposition + gbufferPreviousModelView[3].xyz;
	vec3 diagProjection = vec3(gbufferPreviousProjection[0].x, gbufferPreviousProjection[1].y, gbufferPreviousProjection[2].z);
		 previousPosition = (diagProjection * previousPosition + gbufferPreviousProjection[3].xyz) / -previousPosition.z * 0.5 + 0.5;
		 previousPosition.xy = texcoord + (previousPosition.xy - closestToCamera.xy);

	vec2 d = 0.5-abs(fract(previousPosition.xy*vec2(viewWidth,viewHeight)-texcoord*vec2(viewWidth,viewHeight))-0.5);
	float rej = dot(d,d)*MOTION_REJECTION;
	
	if (previousPosition.x < 0.0 || previousPosition.y < 0.0 || previousPosition.x > 1.0 || previousPosition.y > 1.0) return texture2D(colortex4, texcoord).rgb;

	vec3 albedoCurrent0 = RGB_TO_YCOCG(texture2D(colortex4, texcoord).rgb);
	vec3 albedoCurrent1 = RGB_TO_YCOCG(texture2D(colortex4, texcoord + vec2(texelSize.x, texelSize.y)).rgb);
	vec3 albedoCurrent2 = RGB_TO_YCOCG(texture2D(colortex4, texcoord + vec2(texelSize.x, -texelSize.y)).rgb);
	vec3 albedoCurrent3 = RGB_TO_YCOCG(texture2D(colortex4, texcoord + vec2(-texelSize.x, -texelSize.y)).rgb);
	vec3 albedoCurrent4 = RGB_TO_YCOCG(texture2D(colortex4, texcoord + vec2(-texelSize.x, texelSize.y)).rgb);
	vec3 albedoCurrent5 = RGB_TO_YCOCG(texture2D(colortex4, texcoord + vec2(0.0, texelSize.y)).rgb);
	vec3 albedoCurrent6 = RGB_TO_YCOCG(texture2D(colortex4, texcoord + vec2(0.0, -texelSize.y)).rgb);
	vec3 albedoCurrent7 = RGB_TO_YCOCG(texture2D(colortex4, texcoord + vec2(-texelSize.x, 0.0)).rgb);
	vec3 albedoCurrent8 = RGB_TO_YCOCG(texture2D(colortex4, texcoord + vec2(texelSize.x, 0.0)).rgb);

	if(TAA_sharpness > 0.001 && !sky && !noSharpen){
		vec3 blurCross = albedoCurrent5 + albedoCurrent6 + albedoCurrent7 + albedoCurrent8;
		float centerLuma = albedoCurrent0.r;
		float blurLuma   = (blurCross.r) * 0.25;
		float localDiff  = abs(centerLuma - blurLuma);
		float edgeLimiter = exp(-localDiff * 4.0);
		float sharpenedLuma = centerLuma + (centerLuma - blurLuma) * TAA_sharpness * edgeLimiter;
		albedoCurrent0.r = clamp(sharpenedLuma, 0.0, 1.0);
	}

	vec3 mu = (albedoCurrent0 + albedoCurrent1 + albedoCurrent2 + albedoCurrent3 + albedoCurrent4 + albedoCurrent5 + albedoCurrent6 + albedoCurrent7 + albedoCurrent8) * 0.11111111;
	vec3 sigma = sqrt(max((albedoCurrent0*albedoCurrent0 + albedoCurrent1*albedoCurrent1 + albedoCurrent2*albedoCurrent2 + albedoCurrent3*albedoCurrent3 + albedoCurrent4*albedoCurrent4 + albedoCurrent5*albedoCurrent5 + albedoCurrent6*albedoCurrent6 + albedoCurrent7*albedoCurrent7 + albedoCurrent8*albedoCurrent8) * 0.11111111 - mu*mu, 0.0));
	
	float varianceScale = 1.25 + clamp(rej * 0.55, 0.0, 0.55); 
	vec3 cMin = mu - (varianceScale * sigma);
	vec3 cMax = mu + (varianceScale * sigma);

	vec4 rtMetrics = vec4(texelSize, 1.0/texelSize);
	vec2 position = rtMetrics.zw * previousPosition.xy;
	vec2 centerPosition = floor(position - 0.5) + 0.5;
	vec2 f = position - centerPosition;
	vec2 f2 = f * f;
	vec2 f3 = f * f2;

	float c = 0.82;
	vec2 w0 =        -c  * f3 +  2.0 * c         * f2 - c * f;
	vec2 w1 =  (2.0 - c) * f3 - (3.0 - c)        * f2         + 1.0;
	vec2 w2 = -(2.0 - c) * f3 + (3.0 -  2.0 * c) * f2 + c * f;
	vec2 w3 =         c  * f3 -                c * f2;

	vec2 w12 = w1 + w2;
	vec2 tc12 = rtMetrics.xy * (centerPosition + w2 / w12);
	vec3 centerColor = texture2D(colortex7, vec2(tc12.x, tc12.y)).rgb;

	vec2 tc0 = rtMetrics.xy * (centerPosition - 1.0);
	vec2 tc3 = rtMetrics.xy * (centerPosition + 2.0);
	vec4 color = vec4(texture2D(colortex7, vec2(tc12.x, tc0.y )).rgb, 1.0) * (w12.x * w0.y ) +
				   vec4(texture2D(colortex7, vec2(tc0.x,  tc12.y)).rgb, 1.0) * (w0.x  * w12.y) +
				   vec4(centerColor,                                      1.0) * (w12.x * w12.y) +
				   vec4(texture2D(colortex7, vec2(tc3.x,  tc12.y)).rgb, 1.0) * (w3.x  * w12.y) +
				   vec4(texture2D(colortex7, vec2(tc12.x, tc3.y )).rgb, 1.0) * (w12.x * w3.y );
	vec3 safeColor = max(color.rgb / (color.a + 0.00001), 0.0);
	vec3 albedoPrev = RGB_TO_YCOCG(safeColor);

	vec3 finalcAcc = clamp(albedoPrev,cMin,cMax);
	float isclamped = distance(albedoPrev,finalcAcc) / (albedoPrev.x + 0.00001);
	vec3 diffVector = albedoPrev - albedoCurrent0;
	float lumDiff2 = dot(diffVector, diffVector) / ((albedoPrev.x * albedoPrev.x) + 0.00001);
		  lumDiff2 = 1.0-clamp(lumDiff2,0.0,1.0)*FLICKER_REDUCTION;

	float wCurrent = 1.0 / (1.0 + albedoCurrent0.x);
	float wHistory = 1.0 / (1.0 + albedoPrev.x);
	float blendWeight = clamp(BLEND_FACTOR*lumDiff2+rej+isclamped*ANTI_GHOSTING+0.01,0.0,1.0);
	
	vec3 resultYCoCg = (finalcAcc * wHistory * (1.0 - blendWeight) + albedoCurrent0 * wCurrent * blendWeight) / (wHistory * (1.0 - blendWeight) + wCurrent * blendWeight);
	return YCOCG_TO_RGB(resultYCoCg);
}
#endif

void main() {

	vec4 buffer7 = texture2D(colortex4, texcoord.xy);
	
	#ifdef TAA
		bool sky = texture2D(depthtex0, texcoord.xy).x >= 1.0;
		buffer7.rgb = funTAA(sky);
	#endif

	gl_FragData[0] = buffer7;
}
