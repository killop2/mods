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


varying vec2 lmcoord;
varying vec4 color;

void main() {
	gl_Position = ftransform();
	lmcoord  = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	color = gl_Color;
}