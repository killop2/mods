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


#define gbuffers_skytextured
#include "shaders.settings"

varying vec2 texcoord;
varying vec4 color;
uniform sampler2D texture;

void main() {
#ifdef defskybox
    vec4 albedo = texture2D(texture, texcoord.xy)*color;
	gl_FragData[0] = albedo;
#else
	gl_FragData[0] = vec4(0.0);
#endif	
}
