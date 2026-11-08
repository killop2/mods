#version 120
/* DRAWBUFFERS:35 */
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
#define gbuffers_shadows
#define AA_settings
#include "shaders.settings"

varying vec2 texcoord;
uniform sampler2D colortex4;	//finished deferred + translucent
uniform sampler2D depthtex1;
uniform float viewWidth;
uniform float viewHeight;
uniform mat4 gbufferProjectionInverse;

vec2 texelSize = vec2(1.0/viewWidth,1.0/viewHeight);
vec3 utilScreenSpace(vec3 pos) {
	vec4 iProjDiag = vec4(gbufferProjectionInverse[0].x, gbufferProjectionInverse[1].y, gbufferProjectionInverse[2].zw);
	vec3 p3 = pos * 2.0 - 1.0;
    vec4 fragposition = iProjDiag * p3.xyzz + gbufferProjectionInverse[3];
    return fragposition.xyz / fragposition.w;
}

#ifdef TAA
uniform int framemod8;
uniform float frameTimeCounter;
const vec2[8] offsets = vec2[8](vec2(1./8.,-3./8.),
								vec2(-1.,3.)/8.,
								vec2(5.0,1.)/8.,
								vec2(-3,-5.)/8.,
								vec2(-5.,5.)/8.,
								vec2(-7.,-1.)/8.,
								vec2(3,7.)/8.,
								vec2(7.,-7.)/8.);
#endif

#if defined Volumetric_Lighting || defined Godrays
uniform float far;
uniform float near;
uniform sampler2D depthtex0;
#endif

#ifdef Volumetric_Lighting
uniform sampler2D shadowtex0;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
uniform mat4 gbufferModelViewInverse;

float funVL(float noise, vec3 fragpos) {
    //fast bilateral filter
    vec2 Vstep = vec2(0.0, 1.0) / vec2(viewWidth, viewHeight).xy;
    float depth0 = texture2D(depthtex0, texcoord.xy).x;
    float cdepth = (2.0 * near) / (far + near - depth0 * (far - near));
    float weights[9] = float[9](0.013519, 0.047662, 0.117230, 0.201168, 0.240841, 0.201168, 0.117230, 0.047662, 0.013519);
    float indices[9] = float[9](-4.0, -3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0, 4.0);
    float filterOffset = 0.0, totalWeight = 0.0;
    for (int i = 0; i < 9; ++i) {
        float thresh = (i == 4) ? 1.0 : exp(-abs(texture2D(colortex4, texcoord + indices[i] * Vstep).y - cdepth) * 8.0);
        float w = weights[i] * thresh;
        filterOffset += indices[i] * w;
        totalWeight += w;
    }
    
    mat4 viewToShadow = shadowProjection * shadowModelView * gbufferModelViewInverse;
    vec4 start = viewToShadow * vec4(0.0, 0.0, 0.0, 1.0);
    vec4 end   = viewToShadow * vec4(fragpos, 1.0);
    vec3 dV = (end.xyz - start.xyz) * 0.16666667; //1.0 / 6.0
    vec3 progress = start.xyz + dV * noise;

    float vL = 0.0;
    for (int i = 0; i < 6; i++) {
        vec2 pos = progress.xy * utilDistortion(progress.xy);
        float shadowDepth = texture2D(shadowtex0, pos * 0.5 + 0.5).r;
        vL += step(progress.z * 0.08333333 + 0.5, shadowDepth); //0.5 / 6.0 = 0.08333333
        progress += dV;
    }
    
    return vL * (-fragpos.z * 0.00083333); //final scalar reduction (1 / 1200)
}
#endif

#ifdef Godrays
varying vec2 lightPos;
uniform int isEyeInWater;

float funGodrays(float noise) {
    float baseLength = 0.04 * 23.0; 
    vec2 deltatexcoord = vec2(lightPos - texcoord) * (baseLength / float(grays_sample));
    vec2 noisetc = texcoord + deltatexcoord * noise + deltatexcoord;
    
	float gr = 0.0;
    float comp = 1.0 - near / far / far;
    for (int i = 0; i < grays_sample; i++) {
        float depth = (isEyeInWater == 1.0) ? texture2D(depthtex1, noisetc).x : texture2D(depthtex0, noisetc).x;
        noisetc += deltatexcoord;

        vec2 v = abs(noisetc * 2.0 - 1.0);
        float max_v = max(v.x, v.y);
        float cdist_val = 1.0 - max_v * max_v;
        gr += step(comp, depth) * cdist_val;
    }

    return gr / float(grays_sample);
}
#endif

#ifdef Bloom
vec3 funBloom(){
    const float weights[7] = float[](0.1418, 0.1311, 0.1041, 0.0712, 0.0419, 0.0211, 0.0089);
    const float offsets[7] = float[](0.0, 1.4117, 3.2941, 5.1764, 7.0588, 8.9411, 10.8235);

    vec2 newTC = texcoord * 4.0;
    vec2 stepSize = texelSize * vec2(2.0, 0.0);

    vec3 blur = texture2D(colortex4, newTC).rgb * weights[0];
    for (int i = 1; i < 7; i++) {
        vec2 offs = stepSize * offsets[i];
        
        vec3 sample1 = texture2D(colortex4, newTC + offs).rgb;
        vec3 sample2 = texture2D(colortex4, newTC - offs).rgb;
        
        blur += (sample1 + sample2) * weights[i];
    }
    return clamp(blur, 0.0, 7.0);
}
#endif

void main() {

	//vec4 buffer0 = vec4(0.0, 0.0, 0.0, 1.0);
	vec4 buffer3 = vec4(0.0, 0.0, 0.0, 1.0);
	vec4 buffer5 = vec4(0.0, 0.0, 0.0, 1.0);
	
	#ifdef TAA
		vec3 fragpos1 = utilScreenSpace(vec3(gl_FragCoord.xy*texelSize-offsets[framemod8]*texelSize*0.5, texture2D(depthtex1, gl_FragCoord.xy*texelSize).x));
		float noise = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y + frameTimeCounter * 16.0);
	#else
		float depth1 = texture2D(depthtex1, texcoord.xy).x;
		vec3 fragpos1 = utilScreenSpace(vec3(texcoord, depth1));
		float noise = fract(gl_FragCoord.x * 0.618033988749895 + gl_FragCoord.y * 0.24412852441);
	#endif

	#ifdef Godrays
		buffer3.g = funGodrays(noise);
	#endif

	#ifdef Volumetric_Lighting
		buffer3.b = funVL(noise, fragpos1);
	#endif

	#ifdef Bloom
		buffer5.rgb = funBloom();
	#endif

	gl_FragData[0] = buffer3;
	gl_FragData[1] = buffer5;
}
