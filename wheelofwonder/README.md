# Wheel of Wonder (NeoForge 1.21.1)

A carnival weapon. `/give @s wheelofwonder:wheel_of_wonder` (also in the Combat tab).

**Right click** at a block: the glowing wheel rolls from your feet to that spot (side camera follows it),
stops, lifts off and levitates while spinning and playing a calliope tune.
Every NPC within 14 blocks is mesmerized, whichever way it faces, and floats towards it.
An NPC that gets close becomes a Sable ragdoll stuck to the wheel face and spins with it.

* nobody caught after 4 s of hovering → explosion
* at least one caught → explosion 6 s after the first one sticks
* the explosion scatters the wheel's pieces (rim, bulbs, face shards) and throws all ragdolls away

Shift skips the camera. Thrown NPCs lie until `/wheelofwonder release [radius]` (kickmod's `/unragdoll` works too).

## Walrus PUNCH! (boxing gloves)
`/give @s wheelofwonder:walrus_punch`. Held in the main hand they are worn on both hands (first and third person),
with lightning sparks and frost around them. Normal punches deal +6 damage.

**Left click an NPC** (when not on cooldown): critical uppercut (`critDamage`), the NPC flies straight up as a ragdoll,
hangs at the top for a moment, you leap after it and smash it down; a shockwave when it lands.
Camera, all hard cuts: close-up of the hit → follows the rising body → low angle on your jump → side view of the punch
at the top → orbit on the falling ragdoll → back to you. Settings: `[walrusPunch]` in `wheelofwonder-server.toml`.

## Works with
* **Sable: Ragdolls** (optional): the ragdolls. Called through reflection, like kickmod. Without it NPCs are held on the wheel as normal entities and knocked back.
* **kickmod**: wheel ragdolls get kickmod's tag, so its soft-joint mixin makes them limp. Both camera mixins only act while their own effect runs.
* **Custom NPCs / huggynpc**: any living non-player entity is a target (no bosses, no armor stands).
* Only vanilla particles and sounds; no packets of its own (state goes through entity data).

## Settings
`config/wheelofwonder-server.toml` (fuses, radius, speeds, blast, ragdoll time, music)
and `config/wheelofwonder-client.toml` (camera on/off, distance).

## Textures
`tools/MakeTextures.java` draws the pixel textures (face spiral after the reference picture, item icon, glow):

    java tools/MakeTextures.java src/main/resources/assets/wheelofwonder/textures

Build: `build-d.cmd build` → `build/libs/wheelofwonder-1.21.1-1.0.0.jar` (copy also in `out/`).
