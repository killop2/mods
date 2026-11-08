layout(location = 0) out vec4 fragData0;
layout(location = 1) out vec4 fragData1;
layout(location = 2) out vec4 fragData2;
//buffer 412, reflections in deferred1.fsh
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


#define gbuffers_terrain
#include "shaders.settings"

// vertex

//Moving entities IDs
//See block.properties for mapped ids
#define ENTITY_SMALLGRASS   10031.0
#define ENTITY_LOWERGRASS   10175.0		//lower half only in 1.13+
#define ENTITY_UPPERGRASS	10176.0		//upper half only used in 1.13+
#define ENTITY_SMALLENTS    10059.0
#define ENTITY_LEAVES       10018.0
#define ENTITY_VINES        10106.0
#define ENTITY_LILYPAD      10111.0
#define ENTITY_FIRE         10051.0
#define ENTITY_LAVA   		10010.0
#define ENTITY_EMISSIVE		10089.0 	//emissive blocks defined in block.properties
#define ENITIY_SOULFIRE		10091.0
#define METALLIC_BLOCK		10080.0		//defined in block.properties
#define POLISHED_BLOCK		10081.0
#define ENTITY_NON_DIFFUSED 20000.0
#define ENTITY_WAVING_LANTERN 10090.0
#define ENTITY_WATER		10008.0
#define ENTITY_ICEGLASS		10079.0

// fragment

vec4 utilEncode (vec3 n){
    return vec4(n.xy*inversesqrt(n.z*8.0+8.0) + 0.5, 1.0, 1.0);
}

void voxy_emitFragment(VoxyFragmentParameters parameters) {

	// vertex
	float material = 0.0;
	if(parameters.customId == METALLIC_BLOCK) material = 0.4;
	if(parameters.customId  == POLISHED_BLOCK) material = 0.5;

	//Fix colors on emissive blocks, removed lava as it might cause issues with custom optifine color maps.
	if (parameters.customId == ENTITY_FIRE
	|| parameters.customId == ENTITY_EMISSIVE
	|| parameters.customId == ENITIY_SOULFIRE	
	|| parameters.customId == ENTITY_WAVING_LANTERN){
	material = 0.6;
	}

	//if(mc_Entity.x == ENITIY_SOULFIRE || mc_Entity.x == 10090.0) texcoord.z = 0.85; //lightmap change
	//if(parameters.customId == ENTITY_LAVA) material = 0.6;

	//Translucent blocks
	if (parameters.customId == ENTITY_VINES
	|| parameters.customId == ENTITY_SMALLENTS
	|| parameters.customId == ENTITY_NON_DIFFUSED
	|| parameters.customId == ENTITY_LILYPAD
	|| parameters.customId== ENTITY_LAVA
	|| parameters.customId == ENTITY_LEAVES
	|| parameters.customId == ENTITY_SMALLGRASS
	|| parameters.customId == ENTITY_UPPERGRASS
	|| parameters.customId == ENTITY_LOWERGRASS){
	material = 0.7;
	}

	if(parameters.customId == ENTITY_ICEGLASS) material = 0.9;

	// fragment 
	vec4 albedo = parameters.sampledColour * parameters.tinting;
	if(parameters.customId == ENTITY_WATER) { albedo.a = 0.85; material = 0.8; }

	vec3 lightmap_mat = vec3(parameters.lightMap.xy, material);

	vec3 normal = vec3(0.0);
	switch (uint(parameters.face) >> 1u) {
		case 0u:
		normal.xyz = vxModelView[1].xyz;
		break;
		case 1u:
		normal.xyz  = vxModelView[2].xyz;
		break;
		case 2u:
		normal.xyz  = vxModelView[0].xyz;
		break;
	}
	if ((parameters.face & 1) == 0) {
		normal.xyz  = -normal.xyz ;
	}

    fragData0 = albedo;
	fragData1 = vec4(lightmap_mat, 1.0);
    fragData2 = utilEncode(normal); 
}