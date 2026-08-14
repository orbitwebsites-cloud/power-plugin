# Power SMP Origins — Minecraft 1.20.2

An [Origins](https://modrinth.com/mod/origins) datapack that rebuilds three Power SMP kits as
choosable origins. Everything here is data — no Java, no Paper plugin.

| Origin | Source kit | Impact |
| --- | --- | --- |
| **The Honored One** | `CrazyTNT2CoolKit` — Limitless / Six Eyes / Domain Expansion | 3 |
| **Voidwalker** | `VoidwalkerKit` — Shadow Step / Grasp of Eylis / Illusory Realm | 3 |
| **Mace Massacre** | `TechKnightKit` — the soulbound weapon and its hardware | 3 |

Only kits that use the plugin's domain system were converted (`CrazyTNT2Cool` opens Unlimited Void,
`Voidwalker` opens the Illusory Realm), plus TechKnight.

## Requirements

- Minecraft **1.20.2** (Fabric)
- **Origins 1.12.10** for 1.20.2, which bundles **Apoli 2.11.11**
- Fabric API 0.91.6+1.20.2
- Origins must be installed on both client and server.

`pack_format` is `18`, the data pack format for 1.20.2.

## Installing

Copy this whole `origins-datapack` folder (or a zip of its contents — `pack.mcmeta` must sit at the
zip root) into `<world>/datapacks/`, then `/reload`, then use an Orb of Origin or `/origin set
@s origin powersmp:honored_one` to pick one.

The three origins are appended to the default `origins:origin` layer, so they show up alongside the
vanilla Origins choices rather than replacing them.

Several abilities issue commands (`/damage`, `/particle`, `/title`). Apoli runs those at permission
level 2, which is available by default on a normal server — no `enable-command-block` or operator
setup needed.

## Controls

Origins gives you two ability keys. To fit ten abilities on them, each key is read together with
what you are doing when you press it, and TechKnight's utilities sit on the offhand-swap key.

**The Honored One**

| Input | Ability | Cooldown |
| --- | --- | --- |
| Primary | Cursed Technique Lapse: Blue | 12s |
| Sneak + Primary | Cursed Technique Reversal: Red | 18s |
| Sprint + Primary | Hollow Purple | 90s |
| Secondary | Limitless Warp | 4s |
| Sneak + Secondary | Reverse Cursed Technique | 45s |
| Sprint + Secondary | Domain Expansion: Unlimited Void | 180s |

Passive: **Six Eyes** (night vision, Speed II, Haste II, Regeneration) and **Infinity** (90% of all
incoming damage is erased, and melee attackers are thrown off you).

**Voidwalker**

| Input | Ability | Cooldown |
| --- | --- | --- |
| Primary | Shadow Step | 6s |
| Secondary | Grasp of Eylis | 90s |
| Sneak + Secondary | Illusory Realm | 300s |

**Mace Massacre**

| Input | Ability | Cooldown |
| --- | --- | --- |
| Primary | Earthbreaker | 25s |
| Sneak + Primary | Fortify | 30s |
| Sprint + Primary | Shockwave | 20s |
| Secondary | Reflect Shield | 40s |
| Sneak + Secondary | Overload | 45s |
| Sprint + Secondary | Grapple Shot | 15s |
| Swap offhand | Restock | 5h |
| Sneak + Swap offhand | Decoy | 30s |
| Sprint + Swap offhand | XP Bottles | 1s |

Passives: **Massacre** (the weapon is handed back on origin choice and on every respawn) and
**Shield Ignored** (half of your hits deal magic damage that a raised shield does not stop).

Note that the offhand-swap key still swaps your offhand as well as firing the ability.

## What changed in translation, and why

A Paper plugin can do things a datapack cannot. Where the two disagree, this is what was done:

- **No Mace.** The Mace item arrives in 1.21, so TechKnight's soulbound mace is an unbreakable
  netherite axe named *Massacre* (Sharpness V, Knockback II, Curse of Vanishing). The kill-counting
  enchantment ladder — Density → Breach → Wind Burst — has no 1.20.2 equivalent and is not modelled;
  the weapon is handed out at a fixed strength instead.
- **Infinity reduces rather than cancels.** The plugin cancels non-entity damage outright and lets
  void damage through so nobody gets stranded under the world. Damage-source filtering is fragile in
  data, so this is a flat 90% reduction on everything — void included — which keeps the passive
  lethal-proof without making the holder unkillable in a pit.
- **Illusory Realm is not a separate world.** The plugin builds a sealed void arena with every power
  switched off. A datapack cannot generate and manage that, so the realm is a 20-second seal in
  place: everyone within 16 blocks is blinded, slowed, weakened and made to glow, while the caster
  gets night vision, Strength II and Resistance.
- **Limitless Warp and Shadow Step are dashes, not teleports.** Apoli has no aimed teleport, so both
  set velocity along your look direction and zero your fall distance. Shadow Step still requires a
  target in front of you and still blinds and slows it.
- **Red is a burst, not a true cone.** It detonates at the point you are aiming at and throws
  everything within 28 blocks away from you.
- **Unlimited Void's stun** is Slowness 251 + Mining Fatigue 251 + negative Jump Boost, which roots a
  player in place, alongside Darkness, Blindness, Nausea and 3 hearts of damage.
- **Reflect Shield and Overload use cooldown powers as timers** (`reflect_window`, `overload_window`,
  `overload_spent`) rather than scheduled tasks. Overload still empowers exactly one hit.
- **Restock's loadout is fixed** — pearls, gapples, an enchanted gapple, steak, obsidian, a totem and
  arrows. There is no in-game loadout editor; edit `powers/restock.json` to change it.
- **Decoy** spawns a glowing armour stand that is cleared by `powersmp:clear_decoys` after 10s. It
  does not pull mob aggro the way the plugin's version does — nothing in data does that reliably.

## Layout

```
pack.mcmeta
data/powersmp/origins/          the three origins
data/powersmp/powers/           every power, one file each
data/powersmp/functions/        decoy cleanup
data/origins/origin_layers/     appends the three origins to the default layer
```
