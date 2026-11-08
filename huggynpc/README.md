# Huggy NPC (NeoForge 1.21.1)

Animated NPC models for Custom NPCs (and as standalone mobs): Huggy Wuggy, Mommy Long Legs, Doey, CatNap.
Each has its own spawn egg ("... NPC") that places a Custom NPC with that look.

Mommy, Doey and CatNap were converted from FBX with `tools/fbxconv/FbxToGltf.java` and then
`SkinToMc.java ... voxel:N` (rebuilt from cubes, colours in a small palette texture, root motion removed).
Their source files came without a licence note: check the licence before sharing the mod.

## Use with Custom NPCs
1. Put `out/huggynpc-1.21.1-1.0.0.jar` into `.minecraft/mods` next to CustomNPCs.
2. NPC Wand -> click an NPC -> Display -> Model (the button next to the skin) -> **Entities** tab -> **Huggy Wuggy**.
   The skin is set automatically. The NPC keeps all Custom NPCs AI, stats, dialogs and physics;
   only the look and the hitbox (0.9 x 2.8 blocks) come from Huggy.
3. Save it in the Cloner (Clone tool) to reuse it as a template.

Animations are picked automatically: idle, walk, run (by speed), attack (on every hit),
jump (in the air), crouch / crouch-walk (Custom NPCs animation "Crouch" or "Crawl"), sit ("Sit" or riding), dance ("Dance").
The head follows where the NPC looks.

## Without Custom NPCs
Spawn egg "Huggy Wuggy Spawn Egg" (Spawn Eggs tab or `/give @s huggynpc:huggy_spawn_egg`):
a neutral mob that wanders and fights back.

## Settings
`config/huggynpc-client.toml`: walk/run speed thresholds, attack animation speed, head tracking.
Changes apply without restarting.

## Rebuilding the model
`tools/fbxconv/SkinToMc.java` converts the glTF (skeleton, skin, animations) into `models/entity/huggy.skin`:

    java -cp gson.jar SkinToMc.java convert scene.gltf baseColor.png huggy.skin prev 2.8 20 idle=A_Huggy_Idle walk=A_Huggy_Walk run=A_Huggy_Run attack=A_Huggy_Attack,once jump=A_Huggy_Jump crouch=A_Huggy_CrouchIdle crouchwalk=A_Huggy_CrouchWalk sit=A_Huggy_SitIdle dance=A_Huggy_Dance_Bedrock

`2.8` is the height in blocks, `20` the sampling rate. Any of the model's 38 animations can be mapped this way.

## Credits
This work is based on "Project Playtime | Voxel Huggy"
(https://sketchfab.com/3d-models/project-playtime-voxel-huggy-fff4776e9aa743ecb8d9fca78b13e358)
by Xoffly (https://sketchfab.com/Xoffly) licensed under CC-BY-4.0 (http://creativecommons.org/licenses/by/4.0/)
