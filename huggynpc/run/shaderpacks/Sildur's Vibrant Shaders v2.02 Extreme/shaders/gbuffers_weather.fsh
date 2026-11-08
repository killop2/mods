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


#define gbuffers_weather
#include "shaders.settings"

varying vec4 color;
varying vec2 texcoord;
uniform sampler2D texture;

void main() {
	vec4 albedo = texture2D(texture, texcoord.xy) * color * WeatherIntensity;

	gl_FragData[0] = albedo;
}