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
#include "shaders.settings"

#if defined Shadows || defined Volumetric_Lighting
varying vec4 color;
varying vec4 texcoord;
uniform sampler2D texture;
uniform int blockEntityId;
uniform int entityId;
#endif

void main() {

#if defined Shadows || defined Volumetric_Lighting
	vec4 albedo = texture2D(texture, texcoord.xy) * color;
	
	#if MC_VERSION >= 11300			//color.rgb in 1.12.2 and below is only white
		if(texcoord.z > 0.9)albedo.rgb = color.rgb;				//don't texture water
	#endif
	if(texcoord.w > 0.9)albedo = vec4(0.0);					//disable shadows on entities defined in vertex shadows
	if(entityId == 11000.0)albedo *= 0.0;					//remove lightning strike shadow.
	#if MC_VERSION < 11601									//blockEntityId broken in 1.16.1, causes shadow issue, used to remove beam shadows, 10089 is the id of all emissive blocks but only beam is a block entity
	if(blockEntityId == 10089.0) albedo *= 0.0;
	#endif
	gl_FragData[0] = albedo;
#else
	gl_FragData[0] = vec4(0.0);
#endif	
}