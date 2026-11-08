#version 120
/* DRAWBUFFERS:46 */
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


#define deferred0
#define gbuffers_shadows
#define lightingColors
#define AA_settings
#define Fog_settings
#include "shaders.settings"

varying vec2 texcoord;
varying vec3 sunVec;
varying vec3 upVec;
varying vec3 sky1;
varying vec3 sky2;
varying vec3 nsunlight;
varying vec3 sunlight;
const vec3 moonlight = vec3(0.0025, 0.0045, 0.007);
varying vec3 rawAvg;
varying vec3 cloudColor;
varying vec3 cloudColor2;

uniform sampler2D depthtex1;

varying float tr2;
varying float eyeAdapt;
varying float SdotU;
varying float sunVisibility;
varying float moonVisibility;

uniform sampler2D colortex1;	// lightmap, mats
uniform sampler2D colortex2;	// normal, PCSS
uniform sampler2D colortex4;	// albedo
uniform sampler2D depthtex0;
uniform sampler2D depthtex2;
uniform sampler2D noisetex;
uniform sampler2D shadowcolor0;
uniform sampler2DShadow shadowtex0;
uniform sampler2DShadow shadowtex1;

uniform vec3 skyColor;
uniform vec3 cameraPosition;
uniform vec3 shadowLightPosition;

uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;

uniform int isEyeInWater;
uniform ivec2 eyeBrightnessSmooth;

uniform float far;
uniform float viewWidth;
uniform float viewHeight;
uniform float nightVision;
uniform float rainStrength;
uniform float frameTimeCounter;

#ifdef HandLight
uniform int heldBlockLightValue;
uniform int heldBlockLightValue2;
#endif

uniform bool isNether;

vec3 utilScreenSpace(vec3 pos) {
	vec4 iProjDiag = vec4(gbufferProjectionInverse[0].x, gbufferProjectionInverse[1].y, gbufferProjectionInverse[2].zw);
	vec3 p3 = pos * 2.0 - 1.0;
    vec4 fragposition = iProjDiag * p3.xyzz + gbufferProjectionInverse[3];
    return fragposition.xyz / fragposition.w;
}
#define diagonal3(mat) vec3((mat)[0].x, (mat)[1].y, (mat)[2].z)

#ifdef DISTANT_HORIZONS
	uniform int dhRenderDistance;
	uniform sampler2D dhDepthTex0;
#endif

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
#ifdef VOXY
	uniform int vxRenderDistance;
#endif

vec3 funFog(vec3 albedo, vec3 skyC, vec3 fragpos, float skyL) {
	#if defined(IS_IRIS) || MC_VERSION >= 11802	
		vec3 fogFallback = fogColor;
	#else
		vec3 fogFallback = gl_Fog.color.rgb;
	#endif
	
	vec3 fogC = mix(fogFallback * 0.01, skyC, mix(skyL,1.0,clamp((eyeBrightnessSmooth.y/255.0-0.125)*4.0,0.0,1.0)));	//switch to default fog in caves
	#ifdef defskybox
		fogC = pow(fogFallback * 0.7, vec3(3.2));
	#endif

	float dist = length(fragpos);
	float newFar = far;
	
	#ifdef DISTANT_HORIZONS
   		newFar = max(far, float(dhRenderDistance * 16.0));
		float depth2 = texture2D(depthtex2, texcoord.xy).x;
		float dhDepth0 = texture2D(dhDepthTex0, texcoord.xy).x;
		if (dhDepth0 < 1.0 && (depth2 >= 1.0 || dhDepth0 >= depth2 - 0.00001)) {
			dist = length(utilScreenSpace(vec3(texcoord.xy, dhDepth0))) * 256.0;
		}
	#endif 
	#ifdef VOXY
    	newFar = max(far, float(vxRenderDistance * 16.0));
	#endif
	
	float fogScaling = (isEyeInWater == 1.0) ? (uFogDensity - 1.0) * 11.5 : (wFogDensity - 1.0) * 11.5;	//make fog scaling more intuitive for end users
	float fogDistance = dist / newFar * 12.5 - 11.5 + fogScaling;

	float toggleFog = 1.0;
	#ifndef Underwater_Fog
    	if (isEyeInWater == 1.0) toggleFog = 0.0;
	#endif

	//blend vanilla fog, underwater, lava, snow, use if statement for fog scaling.
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

#ifndef defskybox
uniform int moonPhase;

vec3 funSun(vec3 fposition, vec3 color, float vis) {
	vec3 sVector = normalize(fposition);
	float angle = (1.0 - max(dot(sVector,sunVec), 0.0)) * 650.0;
	float sun = exp(-angle*angle*angle);
		  sun *= (1.0 - rainStrength * 1.0)*sunVisibility;

	vec3 sunlightB = mix(pow(sunlight, vec3(1.0)) * 44.0, vec3(0.25,0.3,0.4), rainStrength * 0.8);
	if(isEyeInWater	== 1.0)sunlightB = mix(pow(moonlight * 40.0, vec3(1.0)) * 44.0, vec3(0.25,0.3,0.4), rainStrength * 0.8);	//use moonlight color for underwater sun

	return mix(color, sunlightB, sun * vis);
}

float utilSmoothstep(float edge0, float edge1, float x) {
	float t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
	return t * t * (3.0 - 2.0 * t);
}

vec3 funMoon(vec3 fposition, vec3 color, float vis) {
	vec3 sVector = normalize(fposition);
	float distToMoon = acos(clamp(dot(sVector, -sunVec), -1.0, 1.0));
	float moonRadius = 0.038;
	float glowMaxRadius = moonRadius * 4.0;
	float moonDisk = 1.0 - utilSmoothstep(moonRadius - 0.001, moonRadius + 0.001, distToMoon);
	float moon = 0.0;
	float glow = 0.0;
	
	float baseGlow = pow(1.0 - utilSmoothstep(moonRadius, glowMaxRadius, distToMoon), 3.0) * 0.04;

	if (moonPhase == 4) {
		//new moon
		float innerDisk = 1.0 - utilSmoothstep(moonRadius - 0.006, moonRadius - 0.004, distToMoon);
		moon = clamp(moonDisk - innerDisk, 0.0, 1.0);
		glow = baseGlow * 0.1;
	} else if (moonPhase == 0) {
		moon = moonDisk;
		glow = baseGlow * 0.1; 
	} else {
		vec3 shadowAxis = normalize(cross(-sunVec, vec3(0.0, 1.0, 0.0)));
		
		float phaseOffsets[8];
		phaseOffsets[0] = 0.0;   //Full Moon
		phaseOffsets[1] = 1.5;   //Waning Gibbous
		phaseOffsets[2] = 0.75;  //Last Quarter
		phaseOffsets[3] = 0.3;   //Waning Crescent
		phaseOffsets[4] = 0.0;   //New Moon
		phaseOffsets[5] = 0.3;   //Waxing Crescent
		phaseOffsets[6] = 0.75;  //First Quarter
		phaseOffsets[7] = 1.5;   //Waxing Gibbous
		
		float finalOffset = phaseOffsets[moonPhase];
		float sweepDirection = (moonPhase > 4) ? 1.0 : -1.0;
		vec3 shadowVector = normalize(-sunVec + shadowAxis * finalOffset * moonRadius * sweepDirection);
		float distToShadow = acos(clamp(dot(sVector, shadowVector), -1.0, 1.0));
		
		float shadowDisk = 1.0 - utilSmoothstep(moonRadius - 0.001, moonRadius + 0.001, distToShadow);
		moon = clamp(moonDisk - shadowDisk, 0.0, 1.0);
		
		float glowShadowDisk = 1.0 - utilSmoothstep(glowMaxRadius - 0.05, glowMaxRadius + 0.05, distToShadow);
		glow = clamp(baseGlow - glowShadowDisk, 0.0, 1.0);
	}

	float finalMoonMask = max(moon, glow) * (1.0 - rainStrength) * moonVisibility;
	vec3 moonlightC = mix(pow(moonlight * 20.0, vec3(1.0)) * 24.0, vec3(0.25, 0.3, 0.4), rainStrength * 0.8);
	return mix(color, moonlightC, finalMoonMask * vis);
}
#endif

vec3 funSkyColor(vec3 fposition) {
	vec3 sVector = normalize(fposition);

	float invRain07 = 1.0 - rainStrength * 0.6;
	float cosT = dot(sVector, upVec);
	float mCosT = max(cosT, 0.0);
	
	float absCosT = 1.0 - max(cosT * 0.82 + 0.26, 0.2);
	float cosY = dot(sunVec, sVector);
	float Y = acos(cosY); 

	//luminance base
	float L = (1.0 - exp(-0.22 / max(mCosT, 0.0001)));
	float A = 1.0 + 0.3 * cosY * cosY;

	//gradient blending
	vec3 grad1 = mix(sky1, sky2, absCosT * absCosT);
	float sunscat = max(cosY, 0.0);
	
	float rainScale = 0.9 - rainStrength * 0.45;
	float sdotuMask = clamp(-SdotU * 4.0 + 3.0, 0.0, 1.0) * 0.65 + 0.35;
	float grad3Mix = sunscat * sunscat * (1.0 - mCosT) * rainScale * sdotuMask + 0.1;
	vec3 grad3 = mix(grad1, nsunlight, grad3Mix);

	float expDY = exp(-3.5 * Y);
	float expDY2 = 0.0000167389 * (1.0 / max(expDY, 1e-7)); 
	float L2 = L * (8.0 * expDY2 + A);

	vec3 nMoon = normalize(moonlight);
	float lMoon = length(moonlight);
	vec3 moonlight2 = (nMoon * nMoon * nMoon) * lMoon;
	vec3 moonlightRain = vec3(0.25, 0.3, 0.4) * (lMoon * 1.82574);

	vec3 gradN = mix(moonlight, moonlight2, 1.0 - L2 * 0.5);
	gradN = mix(gradN, moonlightRain, rainStrength) * 4.0;

	float sunFactor = L * (8.0 * expDY + A);
	float sunScalar = sunVisibility * length(rawAvg) * (0.85 + rainStrength * 0.425);
	vec3 sunFinal = pow(max(sunFactor, 0.0), invRain07) * sunScalar * grad3;

	float moonFactor = L2 * 1.2 + 1.2;
	vec3 moonFinal = 0.2 * pow(max(moonFactor, 0.0), invRain07) * moonVisibility * gradN;

	return sunFinal + moonFinal;
}

#if Clouds == 2 || Clouds == 4
float subSurfaceScattering(vec3 vec,vec3 pos, float N) {
	return pow(max(dot(vec,normalize(pos)),0.0),N)*(N+1)/6.28;
}

float noisetexture(vec2 coord){
	return texture2D(noisetex, coord).x;
}

vec3 funClouds2D(vec3 fposition, vec3 color) {
const float r = 3.2;
const vec4 noiseC = vec4(1.0,r,r*r,r*r*r);
const vec4 noiseWeights = 1.0/noiseC/dot(1.0/noiseC,vec4(1.0));

vec3 tpos = vec3(gbufferModelViewInverse * vec4(fposition, 0.0));
tpos = normalize(tpos);

float cosT = max(dot(fposition, upVec),0.0);

float wind = abs(frameTimeCounter*0.0005-0.5)+0.5;
float distortion = wind * 0.045;
	
float iMult = -log(cosT)*2.0+2.0;
float heightA = (400.0+300.0*sqrt(cosT))/(tpos.y);

for (int i = 1;i<22;i++) {
	vec3 intersection = tpos*(heightA-4.0*i*iMult); 			//curved cloud plane
	vec2 coord1 = intersection.xz/200000.0+wind*0.05;
	vec2 coord = fract(coord1/0.25);
	
	vec4 noiseSample = vec4(noisetexture(coord+distortion),
							noisetexture(coord*noiseC.y+distortion),
							noisetexture(coord*noiseC.z+distortion),
							noisetexture(coord*noiseC.w+distortion));

	float j = i / 22.0;
	coord = vec2(j+0.5,-j+0.5)/noiseTextureResolution + coord.xy + sin(coord.xy*3.14*j)/10.0 + wind*0.02*(j+0.5);
	
	vec2 secondcoord = 1.0 - coord.yx;
	vec4 noiseSample2 = vec4(noisetexture(secondcoord),
							 noisetexture(secondcoord*noiseC.y),
							 noisetexture(secondcoord*noiseC.z),
							 noisetexture(secondcoord*noiseC.w));

	float finalnoise = dot(noiseSample*noiseSample2,noiseWeights);
	float cl = max((sqrt(finalnoise*max(1.0-abs(i-11.0)/11*(0.15-1.7*rainStrength),0.0))-0.55)/(0.65+2.0*rainStrength)*clamp(cosT*cosT*2.0,0.0,1.0),0.0);

	float cMult = max(pow(30.0-i,3.5)/pow(30.,3.5),0.0)*6.0;

	float sunscattering = subSurfaceScattering(sunVec, fposition, 75.0)*pow(cl, 3.75);
	float moonscattering = subSurfaceScattering(-sunVec, fposition, 75.0)*pow(cl, 5.0);
	
	color = color*(1.0-cl)+cl*cMult*mix(cloudColor2*4.75,cloudColor,min(cMult,0.875)) * 0.05 + sunscattering+moonscattering;
	}
return color;
}
#endif

#if Clouds == 3 || Clouds == 4
vec3 funClouds3D(in vec3 pos, in vec3 color, const int cloudIT, float dither, bool isReflection) {
    #ifdef TAA
		if(isReflection) dither = fract(dither + fract(frameTimeCounter * 128.0));	//speedup noise for reflections to hide distortions
    #endif 

    float maxHeight = cloud_height + 200.0;
    vec3 dV_view = pos.xyz;
    vec3 progress_view = vec3(0.0);
    pos = pos * 2200.0 + cameraPosition; 
    
    if (cameraPosition.y <= cloud_height) {
        float maxHeight2 = min(maxHeight, pos.y);    
        dV_view *= -(maxHeight2 - cloud_height) / dV_view.y / float(cloudIT);
        progress_view = dV_view * dither + cameraPosition + dV_view * (maxHeight2 - cameraPosition.y) / dV_view.y;
        if (pos.y < cloud_height) return color;    
    }
    else if (cameraPosition.y < maxHeight) {
        if (dV_view.y <= 0.0) {
            float maxHeight2 = max(cloud_height, pos.y);
            dV_view *= abs(maxHeight2 - cameraPosition.y) / abs(dV_view.y) / float(cloudIT);
            progress_view = dV_view * dither + cameraPosition + dV_view * float(cloudIT);
            dV_view *= -1.0;
        } else {
            float maxHeight2 = min(maxHeight, pos.y);
            dV_view *= -abs(maxHeight2 - cameraPosition.y) / abs(dV_view.y) / float(cloudIT);
            progress_view = dV_view * dither + cameraPosition - dV_view * float(cloudIT);
        }
    }
    else {            
        float maxHeight2 = max(cloud_height, pos.y);    
        dV_view *= -abs(maxHeight2 - maxHeight) / abs(dV_view.y) / float(cloudIT);
        progress_view = dV_view * dither + cameraPosition + dV_view * (maxHeight2 - cameraPosition.y) / dV_view.y;
        if (pos.y > maxHeight) return color;    
    }

    float mult = length(dV_view) * 0.00390625; 
    vec3 rawColor = color;
    vec3 smoothColor = color;
    float center = (cloud_height + maxHeight) * 0.5;
    float invDifCenter = 1.0 / (maxHeight - center);

    //cache pre computed exponents for density octaves (exp(j*1.05)*0.6) and weights (exp(-j*0.8))
    // j=0: scale=0.6,    weight=1.0
    // j=1: scale=1.7142, weight=0.4493
    // j=2: scale=4.8996, weight=0.2019
    // j=3: scale=13.999, weight=0.0907
    // Total weight = 1.7419
    vec4 stepsScale = vec4(0.6, 1.7142, 4.8996, 13.999);
    vec4 stepsWeight = vec4(1.0, 0.4493, 0.2019, 0.0907);
    vec3 timeVec = vec3(0.5, 0.0, 0.5);

    for (int i = 0; i < cloudIT; i++) {
        float heightMult = (progress_view.y - center) * invDifCenter;
        vec3 samplePos = progress_view * 0.175 + frameTimeCounter * timeVec;
        float noise = 0.0;

        //cut octaves down for reflections
        int maxOctaves = isReflection ? 2 : 4;

        for (int j = 0; j < maxOctaves; j++) {
            float s = (j == 0) ? stepsScale.x : ((j == 1) ? stepsScale.y : ((j == 2) ? stepsScale.z : stepsScale.w));
            float w = (j == 0) ? stepsWeight.x : ((j == 1) ? stepsWeight.y : ((j == 2) ? stepsWeight.z : stepsWeight.w));
            
            vec3 pOct = (samplePos * s + frameTimeCounter * float(j) * timeVec * 0.6) * 0.0555555; 
            pOct.xz *= 0.5;

            vec3 p = floor(pOct);
            vec3 f = fract(pOct);
            f = sqrt((f * f) * (3.0 - 2.0 * f));
            
            vec2 uv = p.xz + f.xz + p.y * 17.0;
            float xy1 = texture2D(noisetex, uv * 0.015625).x; 
            float xy2 = texture2D(noisetex, (uv * 0.015625) + 0.265625).x; 
            noise += mix(xy1, xy2, f.y) * w;
        }

        //normalize noise scale when skipping octaves
        if (isReflection) noise *= 1.2;

        float cloud = (1.0 - pow(0.4, max((noise * 0.57408) - 0.56 - heightMult * heightMult * 0.3 + rainStrength * 0.2, 0.0) * 2.2)) * 20.0; //rain * 0.16
        float heightFactor = clamp((progress_view.y - cloud_height) * 0.005, 0.0, 1.0); 
        float lightsourceVis = pow(heightFactor, 2.3);
        
        vec3 cloudC = mix(cloudColor2 * 0.05, cloudColor * 0.15, lightsourceVis);
        float blendFactor = 1.0 - exp(-cloud * mult);
        
        rawColor = mix(rawColor, cloudC, blendFactor);

        float edgeSmoothWeight = blendFactor * (1.0 - (dither - 0.5) * 0.45 * heightFactor);
        smoothColor = mix(smoothColor, cloudC, clamp(edgeSmoothWeight, 0.0, 1.0));

        progress_view += dV_view;
    }

    float upperEdgeMask = clamp((progress_view.y - cloud_height) * 0.005, 0.0, 1.0);
    return mix(rawColor, smoothColor, upperEdgeMask * 0.9);    
}

#endif

#ifdef customStars
float funStars(vec3 pos){
 	vec3 p = pos * 256.0;
	vec3 flr = floor(p);
	float fr = length((p - flr) - 0.5);
	flr = fract(flr * 443.8975);
    flr += dot(flr, flr.xyz + 19.19);

 	float intensity = step(fract((flr.x + flr.y) * flr.z), 0.0025) * (1.0 - rainStrength);
	float stars = clamp((fr - 0.5) / (0.0 - 0.5), 0.0, 1.0);	//recreate smoothstep for opengl 120
    	  stars = stars * stars * (3.0 - 2.0 * stars);			//^

 	return stars * intensity;
}
#endif

//bake sky panorama into colortex6 for reflections and lightcolor
vec3 funPanoramaSky(float dither){
	vec3 sky = vec3(0.0, 0.0, 0.0);

	//unwarp screen coordinates into a 3D direction vector sphere
	vec3 skyPos = normalize(vec3(sin((texcoord.x - 0.5) * 6.283185) * cos((texcoord.y - 0.5) * 3.141592),
								 sin((texcoord.y - 0.5) * 3.141592),
								 cos((texcoord.x - 0.5) * 6.283185) * cos((texcoord.y - 0.5) * 3.141592)));

	vec3 reflCpos = normalize(gbufferModelViewInverse * vec4(skyPos, 0.0)).xyz;	
	
	#ifndef defskybox
		sky = pow(funSkyColor(skyPos), vec3(1.4));	//mirror real sky
	#endif

	#ifdef customStars
		sky += funStars(reflCpos) * moonVisibility;
	#endif
	
	#ifdef Cloud_reflection
	#if Clouds == 2 || Clouds == 4
		sky = funClouds2D(skyPos, sky) * 0.65;	//reduce clouds brightness for reflections
	#endif

	#if Clouds == 3 || Clouds == 4
		float cheight = (cloud_height - 32.0);
		if (dot(skyPos, upVec) > 0.0 || cameraPosition.y > cheight) sky = funClouds3D(reflCpos, sky, 3, dither, true) * 0.65;	//reduce clouds brightness for reflections, hardcode low sample rate (3)
	#endif
	#endif

	#ifndef defskybox
		sky = funSun(skyPos, sky, 1.0);				//draw sun after clouds to sample light from
		sky = funMoon(skyPos, sky, 1.0);
	#else
		sky = mix(sky, pow(skyColor * 0.75, vec3(3.2)), 1.0);	//recreate vanilla sky for water
	#endif

	return sky;
}

vec2 texelSize = vec2(1.0/viewWidth,1.0/viewHeight);

#ifdef TAA
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

#if defined raytracedShadows || defined VOXY
uniform float near;

float funRaytraceShadows(vec3 angle, vec3 pos, float dither, float translucent){
    float rayLength = (pos.z + angle.z * far * 1.732 > -near) ? (-near - pos.z) / angle.z : far * 1.732;
    vec3 clipStart = (diagonal3(gbufferProjection) * pos + gbufferProjection[3].xyz) / -pos.z * 0.5 + 0.5;
    vec3 direction = ((diagonal3(gbufferProjection) * (pos + angle * rayLength) + gbufferProjection[3].xyz) / -(pos.z + angle.z * rayLength) * 0.5 + 0.5) - clipStart;
    vec3 stepv = (direction / max(abs(direction.x) / texelSize.x, abs(direction.y) / texelSize.y)) * 4.5;
    
    #ifdef TAA	
        vec3 spos = clipStart + vec3(offsets[framemod8] * texelSize * 0.5, 0.0) + stepv * dither;
    #else
        vec3 spos = clipStart + stepv * dither;
    #endif

    for (int i = 0; i < 16; i++) {
        spos += stepv;
        float depth0 = texture2D(depthtex0, spos.xy).x;   
        // depth check
        if (depth0 < spos.z && abs((far + near - spos.z * (far - near)) / (far + near - depth0 * (far - near)) - 1.0) < 0.01) {
            return translucent * exp2(pos.z * 0.125); //1.0 / 8.0 = 0.125 multiplication step
        }
    }
    return 1.0;
}
#endif

#ifdef Shadows
vec3 funShadows(float noise, float skyL, bool translucent, vec3 fragpos, vec3 normal) {
	float diffuse = (translucent)? dot(normal, normalize(shadowLightPosition)) * 0.35 + 0.65 : clamp(dot(normal, normalize(shadowLightPosition)),0.0,1.0); //translucent = 0.75 before, * 0.35 + 0.65 must always be 1 total
	vec3 finalShading = vec3(diffuse);

	//hand shadows, not fully accurate
	if (texture2D(depthtex2, texcoord.xy).x > texture2D(depthtex0, texcoord.xy).x) fragpos.xyz *= 6.5; 

	if (diffuse > 0.001) {
	vec3 shadowpos = mat3(gbufferModelViewInverse) * fragpos + gbufferModelViewInverse[3].xyz;
		 shadowpos = mat3(shadowModelView) * shadowpos + shadowModelView[3].xyz;
		 shadowpos = diagonal3(shadowProjection) * shadowpos + shadowProjection[3].xyz;
	float distortion = utilDistortion(shadowpos.xy);
		 shadowpos.xy *= distortion;
	vec2 shading = vec2(1.0);

	if (abs(shadowpos.x) < 1.0-1.5/shadowMapResolution && abs(shadowpos.y) < 1.0-1.5/shadowMapResolution && abs(shadowpos.z) < 6.0){  //only if on shadowmap
		float pdepth = 1.412;	//fallback if PCSS shadows are disabled
		const float threshMul = max(2048.0/shadowMapResolution*shadowDistance*0.0078125,0.95);
		float distortThresh = (sqrt(1.0-diffuse*diffuse)/diffuse+0.7)/distortion;
		shadowpos = shadowpos * vec3(0.5,0.5,0.08333333) + vec3(0.5,0.5,0.5);

		#ifdef PCSS
			pdepth = texture2D(colortex2, texcoord.xy).b;
		#endif

		float rdMul = pdepth*distortion*Nearshadowplane*k/shadowMapResolution;
		float bias = translucent? 0.00014 : distortThresh*0.0001666667*threshMul;
		
		vec2 shadows = vec2(0.0);
		float rShadowSamples = 1.0 / float(shadow_samples);
		for(int i = 0; i < shadow_samples; i++){
			float alpha = (float(i) + noise) * rShadowSamples;
			float angle = (noise + alpha * 4.0) * 6.2831853;
			vec2 offsetS = vec2(cos(angle), sin(angle)) * sqrt(alpha);

			float weight = 1.0+(i+noise)*rdMul*rShadowSamples*shadowMapResolution;
			
			shadows.x += shadow2D(shadowtex0,vec3(shadowpos + vec3(rdMul*offsetS,-bias*weight))).x;
		#ifdef ColoredShadows
			shadows.y += shadow2D(shadowtex1,vec3(shadowpos + vec3(rdMul*offsetS,-bias*weight))).x;
		#endif
		}
		shading = shadows * rShadowSamples;
	}
	#if defined raytracedShadows || defined VOXY
		if(shading.x > 0.005)shading.xy *= funRaytraceShadows(shadowLightPosition, fragpos.xyz, noise, float(translucent));
	#endif
	#ifdef ColoredShadows
		finalShading = texture2D(shadowcolor0, shadowpos.xy).rgb*(shading.y-shading.x) + shading.x;
		finalShading *= diffuse;
	#else
		finalShading = vec3(shading.x)*diffuse;
	#endif
		//Prevent light leakage
		finalShading *= mix(skyL,1.0,clamp((eyeBrightnessSmooth.y/255.0-0.125)*4.0,0.0,1.0));
	}
	return finalShading;
}
#endif

#ifdef SSDO
uniform float aspectRatio;
const vec2 check_offsets[25] = vec2[25](vec2(-0.4894566f,-0.3586783f),
									vec2(-0.1717194f,0.6272162f),
									vec2(-0.4709477f,-0.01774091f),
									vec2(-0.9910634f,0.03831699f),
									vec2(-0.2101292f,0.2034733f),
									vec2(-0.7889516f,-0.5671548f),
									vec2(-0.1037751f,-0.1583221f),
									vec2(-0.5728408f,0.3416965f),
									vec2(-0.1863332f,0.5697952f),
									vec2(0.3561834f,0.007138769f),
									vec2(0.2868255f,-0.5463203f),
									vec2(-0.4640967f,-0.8804076f),
									vec2(0.1969438f,0.6236954f),
									vec2(0.6999109f,0.6357007f),
									vec2(-0.3462536f,0.8966291f),
									vec2(0.172607f,0.2832828f),
									vec2(0.4149241f,0.8816f),
									vec2(0.136898f,-0.9716249f),
									vec2(-0.6272043f,0.6721309f),
									vec2(-0.8974028f,0.4271871f),
									vec2(0.5551881f,0.324069f),
									vec2(0.9487136f,0.2605085f),
									vec2(0.7140148f,-0.312601f),
									vec2(0.0440252f,0.9363738f),
									vec2(0.620311f,-0.6673451f)
									);

//modified version of Yuriy O'Donnell's SSDO (License MIT -> https://github.com/kayru/dssdo)
float funSSDO(float noise, vec3 fragpos, vec3 normal){
	float finalAO = 0.0;
	float radius = 0.05 / (fragpos.z);
	const float attenuation_angle_threshold = 0.1;
	const int num_samples = 16;	
	const float ao_weight = 1.0;

	for( int i=0; i<num_samples; ++i ){
	    vec2 texOffset = pow(length(check_offsets[i].xy),0.5)*radius*vec2(1.0,aspectRatio)*normalize(check_offsets[i].xy);
		vec2 newTC = texcoord+texOffset*noise;
	#ifdef TAA
		vec3 t0 = utilScreenSpace(vec3(newTC-offsets[framemod8]*texelSize*0.5, texture2D(depthtex0, newTC).x));
	#else
		vec3 t0 = utilScreenSpace(vec3(newTC, texture2D(depthtex0, newTC).x));
	#endif	
		vec3 center_to_sample = t0.xyz - fragpos.xyz;

		float dist = length(center_to_sample);

		vec3 center_to_sample_normalized = center_to_sample / dist;
		float attenuation = 1.0-clamp(dist/6.0,0.0,1.0);
		float dp = dot(normal, center_to_sample_normalized);

		attenuation = sqrt(max(dp,0.0))*attenuation*attenuation * step(attenuation_angle_threshold, dp);
		finalAO += attenuation * (ao_weight / num_samples);
	}
	return finalAO;
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

void main() {

	vec4 albedo = texture2D(colortex4, texcoord.xy);
		 albedo.rgb = pow(albedo.rgb, vec3(2.2));
		
	vec3 normal = utilDecode(texture2D(colortex2, texcoord.xy).xy);
	vec2 lightmap = texture2D(colortex1, texcoord).xy;
	float depth0 = texture2D(depthtex0, texcoord.xy).x;
	
	float mats = texture2D(colortex1, texcoord.xy).b;
	bool isMetallic = mats > 0.39 && mats < 0.41;
	bool isPolished = mats > 0.49 && mats < 0.51;
	bool isEmissive = mats > 0.59 && mats < 0.61;	
	bool translucent = mats > 0.69 && mats < 0.71;

	#ifdef TAA
		vec3 fragpos0 = utilScreenSpace(vec3(gl_FragCoord.xy*texelSize-offsets[framemod8]*texelSize*0.5, texture2D(depthtex0, gl_FragCoord.xy*texelSize).x));
		float noise = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y + frameTimeCounter * 16.0);
		float dither = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y); 
        	  dither = fract(dither + fract(frameTimeCounter * 12.9898));
	#else
		vec3 fragpos0 = utilScreenSpace(vec3(texcoord, depth0));
		float noise = fract(gl_FragCoord.x * 0.618033988749895 + gl_FragCoord.y * 0.24412852441);
		float dither = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y);
	#endif

	float ao = 1.0;
	#ifdef SSDO
		float occlusion = funSSDO(noise, fragpos0, normal);
		if(isEyeInWater < 0.9) ao = pow(1.0-occlusion, ao_strength);
	#endif

	//Emissive lighting
	#ifdef HandLight
	bool underwaterlava = (isEyeInWater == 1.0 || isEyeInWater == 2.0);
	if(!underwaterlava) lightmap.x = max(lightmap.x, max(max(float(heldBlockLightValue), float(heldBlockLightValue2)) - 1.0 - length(fragpos0), 0.0) / 15.0);
	#endif
	float torch_lightmap = 16.0-min(15.0,(lightmap.x-0.03125)*17.066666667);
	float fallof1 = clamp(1.0 - pow(torch_lightmap*0.0625,4.0),0.0,1.0);
	torch_lightmap = fallof1*fallof1/(torch_lightmap*torch_lightmap+1.0);
	float c_emitted = dot(albedo.rgb, vec3(emissive_R,emissive_G,emissive_B));
	float emitted 		= isEmissive? clamp(c_emitted*c_emitted,0.0,1.0)*torch_lightmap : 0.0;
	vec3 emissiveLightC = vec3(emissive_R,emissive_G,emissive_B) * 0.65;

	//Lighting and colors
	float NdotL = dot(normal,sunVec);
	float NdotU = dot(normal,upVec);
	
	const vec3 moonlight = vec3(0.5, 0.9, 1.8) * Moonlight;

	vec2 visibility = vec2(sunVisibility,moonVisibility);

	float skyL = max(lightmap.y-0.125,0.0)*1.14285714286;	
	float SkyL2 = skyL*skyL;
	float skyc2 = mix(1.0,SkyL2,skyL);

	vec4 bounced = vec4(NdotL,NdotL,NdotL,NdotU) * vec4(-0.14*skyL*skyL,0.33,0.7,0.1) + vec4(0.6,0.66,0.7,0.25);
		 bounced *= vec4(skyc2,skyc2,visibility.x-tr2*visibility.x,0.8);

	float weatherFactor = 1.0 - rainStrength * 0.99;
	vec3 sun_ambient = bounced.w * (vec3(0.24, 1.2, 2.64)+rainStrength*vec3(0.115,-0.759,-2.07))+ (1.6*weatherFactor)*sunlight*(sqrt(bounced.w)*bounced.x*2.4 + bounced.z);
	vec3 moon_ambient = (moonlight*0.7 + moonlight*bounced.y)*4.0;

	//vec3 LightC = mix(sunlight,moonlight,moonVisibility)*tr*(1.0-rainStrength*0.99);
	vec3 LightC = mix(sunlight,moonlight,moonVisibility)*weatherFactor; //remove time check to smooth out day night transition
	vec3 amb1 = (sun_ambient*visibility.x + moon_ambient*visibility.y)*SkyL2*(0.0195+tr2*0.1105);
	float finalminlight = (nightVision > 0.01)? 0.15 : minlight; //add nightvision support but make sure minlight is still adjustable.	
	vec3 ambientC = ao*amb1 + emissiveLightC*(emitted*15.*albedo.rgb + torch_lightmap*ao)*0.66 + ao*finalminlight*min(skyL+0.375,0.5625)*normalize(amb1+0.0001);
	ambientC = max(vec3(0.0), ambientC);	//prevent negative values to fix NaNs on janky drivers.
	/*----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------*/

    #ifdef DISTANT_HORIZONS
		float dhDepth = texture2D(dhDepthTex0, texcoord.xy).x;
		if (dhDepth < 1.0 && (depth0 >= 1.0 || dhDepth < depth0)) depth0 = dhDepth;	//blend depth0 and dhdepth0
	#endif

	vec3 skyC = funSkyColor(fragpos0);
	//sky
	if(depth0 >= 1.0) {
		vec3 cpos = normalize(gbufferModelViewInverse*vec4(fragpos0, 1.0)).xyz;

		#ifndef defskybox
			skyC = pow(max(skyC + (dither * 0.0039215686 - 0.0019607843), 0.0), vec3(1.4));
			skyC = funSun(fragpos0, skyC, 1.0);
			skyC = funMoon(fragpos0, skyC, 1.0);
		#else
			skyC = pow(albedo.rgb*0.75, vec3(2.2)); 
		#endif

		#ifdef customStars
			skyC += funStars(cpos)*moonVisibility;
		#endif

		#if Clouds == 2 || Clouds == 4
			skyC = funClouds2D(normalize(fragpos0), skyC);
		#endif

		#if Clouds == 3 || Clouds == 4
			float cheight = (cloud_height-32.0);
			if (dot(fragpos0, upVec) > 0.0 || cameraPosition.y > cheight)skyC = funClouds3D(cpos, skyC, cloudsIT, dither, false);
		#endif

		albedo.rgb = skyC;	//fog also draws the sky below
	//land
	} else {
	#ifdef Shadows
		vec3 finalShadow = funShadows(noise, skyL, translucent, fragpos0, normal);
		vec3 finalLight = (finalShadow*LightC*(SkyL2*skyL)*2.15+ambientC*(1.0/(SkyL2*skyL*0.5+0.5))*1.4)*0.63;
		albedo.rgb *= finalLight;
	#else
		float dif = translucent? dot(normal, normalize(shadowLightPosition)) * 0.35 + 0.65 : clamp(dot(normal, normalize(shadowLightPosition)),0.0,1.0);
			  dif *= mix(skyL,1.0,clamp((eyeBrightnessSmooth.y/255.0-0.25)*4.0,0.0,1.0)); //fix lighting in caves with shadows disabled.
			  dif *= 0.5;
		vec3 finalLight = (dif*LightC*(SkyL2*skyL)*2.15+ambientC*(1.0/(SkyL2*skyL*0.5+0.5))*1.4)*0.63;
		albedo.rgb *= finalLight;
	#endif

	#ifdef Fog
		//undithered sky for fog / land
		skyC = pow(max(skyC, 0.0), vec3(1.4));
		albedo.rgb = funFog(albedo.rgb, skyC, fragpos0, skyL);	//fill colortex4 with fogcolor at distance
	#endif
	}

	albedo.rgb = (albedo.rgb * pow(eyeAdapt,0.88));
	albedo.rgb = pow(albedo.rgb, vec3(0.454));

	gl_FragData[0] = albedo;
	gl_FragData[1] = vec4(funPanoramaSky(dither), 1.0);
}