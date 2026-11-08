#version 120
/* DRAWBUFFERS:412 */
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


#include "shaders.settings"

varying vec4 color;
varying vec4 texcoord;
varying vec3 normal;

uniform sampler2D texture;
uniform sampler2D depthtex1;

uniform float viewHeight;
uniform float viewWidth;

vec4 encode (vec3 n){
    return vec4(n.xy*inversesqrt(n.z*8.0+8.0) + 0.5, 1.0, 1.0);
}

void main() {

	vec2 newTC = gl_FragCoord.xy / vec2(viewWidth, viewHeight);

    if(texture2D(depthtex1, newTC).x < 0.9995) discard;

	vec4 albedo = texture2D(texture, newTC)*color;
	vec3 lightmap_mat = vec3(texcoord.zw, 0.0);

	gl_FragData[0] = albedo;
	gl_FragData[1] = vec4(lightmap_mat, 1.0);
	gl_FragData[2] = encode(normal);
}