#version 120
/* DRAWBUFFERS:412 */ //4=albedo, 1=lightmap+mats, 2=normal+PCSS
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


#define gbuffers_textured
#define gbuffers_shadows
#define AA_settings
#include "shaders.settings"

/*					
const int colortex1Format = RGB8;			//lightmap, mats
const int colortex2Format = RGB16F;			//normals, PCSS
const int colortex3Format = RGB8;			//empty, Godrays, Volumetric
const int colortex4Format = R11F_G11F_B10F;	//final deferred and translucent
const int colortex5Format = R11F_G11F_B10F;	//bloom
const int colortex6Format = R11F_G11F_B10F;	//panorma sky
const int colortex7Format = RGB16F;			//TAA
const bool 	shadowHardwareFiltering0 = true;
const bool 	shadowHardwareFiltering1 = true;
const float	sunPathRotation	= -40.0;		//[-10.0 -20.0 -30.0 -40.0 -50.0 -60.0 -70.0 -80.0 0.0 10.0 20.0 30.0 40.0 50.0 60.0 70.0 80.0]
*/

varying vec4 color;
varying vec4 texcoord;
varying vec4 normal;
varying vec3 worldpos;
varying vec3 viewVector;
varying mat3 tbnMatrix;

uniform sampler2D texture;
//uniform sampler2D specular;
uniform sampler2D noisetex;
uniform vec4 entityColor;

uniform float frameTimeCounter;
vec3 newnormal = normal.xyz;

vec4 utilEncode (vec3 n, float getPCSS){
    return vec4(n.xy*inversesqrt(n.z*8.0+8.0) + 0.5, getPCSS, 1.0);
}

#if nMap >= 1
#extension GL_ARB_shader_texture_lod : enable
uniform sampler2D normals;
varying float block;
bool isblock = block > 0.0 || block < 0.0; //workaround for 1.16 bugs on block entities
varying float dist;
varying vec4 vtexcoordam; // .st for add, .pq for mul
varying vec2 vtexcoord;

uniform ivec2 atlasSize; 
vec2 atlasAspect = vec2(atlasSize.y/float(atlasSize.x), atlasSize.x/float(atlasSize.y));

mat2 mipmap = mat2(dFdx(vtexcoord.xy*vtexcoordam.pq), dFdy(vtexcoord.xy*vtexcoordam.pq));	
vec4 readNormal(in vec2 coord){
	return texture2DGradARB(normals,fract(coord)*vtexcoordam.pq+vtexcoordam.st,mipmap[0],mipmap[1]);
}

vec4 funPOM(vec4 albedo){
	vec2 newCoord = vtexcoord.xy*vtexcoordam.pq+vtexcoordam.st;
	#if nMap == 2
	if (dist < POM_DIST && viewVector.z < 0.0 && readNormal(vtexcoord.xy).a < 1.0){
		vec2 viewCorrection = max(vec2(vtexcoordam.q/vtexcoordam.p*atlasAspect.x,1.0), vec2(1.0,vtexcoordam.p/vtexcoordam.q*atlasAspect.y));
		const float res_stepths = 0.33 * POM_RES;
		vec2 pstepth = viewCorrection * viewVector.xy * POM_DEPTH / (-viewVector.z * POM_RES);
		vec2 coord = vtexcoord.xy;
		for (int i= 0; i < res_stepths && (readNormal(coord.xy).a < 1.0-float(i)/POM_RES); ++i) coord += pstepth;
	
		newCoord = fract(coord.xy)*vtexcoordam.pq+vtexcoordam.st;
	}
	#endif
	//vec4 specularity = texture2DGradARB(specular, newCoord, dcdx, dcdy);
	vec3 bumpMapping = texture2DGradARB(normals, newCoord, mipmap[0],mipmap[1]).rgb*2.0-1.0;
	newnormal = normalize(bumpMapping * tbnMatrix);

	return albedo = texture2DGradARB(texture, newCoord, mipmap[0],mipmap[1])*color;
}
#endif

#if defined Shadows && defined PCSS
uniform vec3 shadowLightPosition;
uniform sampler2D shadowtex0;
varying vec4 PCSS_data;
varying vec4 vertexShadowPosDiffuse;
#extension GL_EXT_gpu_shader4 : enable
#define ffstep(x,y) clamp((y - x) * 1e35, 0.0, 1.0)

float funPCSS(float noise) {
	float pdepth = 1.412;
	if (vertexShadowPosDiffuse.w > 0.001) {
		if (abs(vertexShadowPosDiffuse.x) < 1.0 - 1.5 / shadowMapResolution && abs(vertexShadowPosDiffuse.y) < 1.0 - 1.5 / shadowMapResolution && abs(vertexShadowPosDiffuse.z) < 6.0) {
			float diffthreshM = PCSS_data.r;
			float invS = PCSS_data.g;
			float stepBias = PCSS_data.b;
			float searchScale = PCSS_data.a;
			
			vec2 counter = vec2(0.0);
			for (int i = 0; i < VPS_samples; i++) {
				float alpha = (float(i) + noise) * invS;
				float angle = (noise + alpha * 4.0) * 6.2831853;
				
				float depth = texture2D(shadowtex0, vertexShadowPosDiffuse.xy + (vec2(cos(angle), sin(angle)) * sqrt(alpha)) * searchScale).x;
				float block = ffstep(depth, vertexShadowPosDiffuse.z - float(i) * stepBias - diffthreshM);
				counter += vec2(block, depth * block);
			}
			pdepth = clamp(max(vertexShadowPosDiffuse.z - ((counter.x >= 0.9) ? counter.y / counter.x : vertexShadowPosDiffuse.z), 0.0) * 1500.0, 0.0, 20.0) * 1.4294 + 1.412;
		}
	}
	return pdepth;
}
#endif

void main() {

	vec4 albedo = texture2D(texture, texcoord.xy)*color;
	vec3 lightmap_mat = vec3(texcoord.zw, normal.a);

	#ifdef TAA
		float noise = fract(0.75487765 * gl_FragCoord.x + 0.56984026 * gl_FragCoord.y + frameTimeCounter * 16.0);
	#else
		float noise = fract(gl_FragCoord.x * 0.618033988749895 + gl_FragCoord.y * 0.24412852441);
	#endif

	float getPCSS = 1.0;
	#if defined Shadows && defined PCSS
		getPCSS = funPCSS(noise);
	#endif

	#if nMap >= 1
 	if(isblock)albedo = funPOM(albedo);
	#endif

	#ifdef MobsFlashRed
		albedo.rgb = mix(albedo.rgb, entityColor.rgb, entityColor.a);
	#endif

/* // renders without it right now
	//Lightning rendering
	if(entityId == 11000.0){
		float night = clamp((worldTime-13000.0)/300.0,0.0,1.0)-clamp((worldTime-22800.0)/200.0,0.0,1.0);
		finalColor = vec3(0.025, 0.03, 0.05) * (1.0-0.75*night);
		albedo.a = 1.0;
	}
*/
	gl_FragData[0] = albedo;
	gl_FragData[1] = vec4(lightmap_mat, 1.0);
	gl_FragData[2] = utilEncode(newnormal.xyz, getPCSS);
}