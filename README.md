# PowerSMP

Bespoke per-player power kits for a Paper SMP. Every player has their own kit; nothing is shared
from a common ability pool.

- **Target:** Paper 1.21.1+, Java 21, Maven
- **Build:** `mvn package` → `target/powersmp-0.1.0.jar`
- **Configure:** `plugins/PowerSMP/kits.yml` (every tuning number lives there), `/powersmp reload`

> **This has not been compiled.** The build environment could only reach Maven Central, and
> `paper-api` is published on `repo.papermc.io`, which the network policy blocked. The sources were
> verified to be syntactically clean and the YAML configs parse, but the Bukkit-facing calls have
> not been checked against the real API. Run `mvn package` locally before deploying.

---

## Commands

| Command | Who | What |
|---|---|---|
| `/stance red\|blue\|green\|none` | stance kits (Mavricc) | Switch stance |
| `/power` / `/power list` | anyone with a kit | List abilities, cooldowns, unlock state |
| `/power <ability>` | kit owner | Fire an ability |
| `/powersmp reload` | admin | Re-read `kits.yml` |
| `/powersmp info [player]` | admin | Kit, stance, kills, spear tier, unlock state |
| `/powersmp grant\|revoke <player> <power>` | admin | Hand-manage unlocks |
| `/powersmp kills <player> [n]` | admin | Read or set the kill counter |
| `/powersmp spear [player]` | admin | Issue a spear |
| `/xp` (alias `/xpbottles`) | TechKnightGaming | Fill inventory with XP bottles |

Sneak + right-click with an empty hand fires your kit's primary ability
(`general.sneak-right-click-primary`).

---

## Architecture

```
com.powersmp
├── PowerSMP.java          main class; owns the single shared 1/sec kit tick
├── kit/                   PowerKit interface, Ability, KitRegistry, impl/ (one class per player)
├── stance/                Stance, StanceManager (stances + Mushroom Affinity), StanceCommand
├── food/                  MushroomHungerService, FoodProfile
├── cooldown/              CooldownManager (+ action-bar readout)
├── combat/                ComboTracker, FreezeUtil
├── progression/           Power catalogue, UnlockManager
├── data/                  DataStore, PlayerData (YAML keyed by UUID)
├── item/                  SpearItem
├── mirage/                MirageProvider + ArmorStandMirageProvider
├── event/                 DraconicEvolutionEvent
└── util/                  Attributes, Effects, Crits, Keys, Text
```

The shared services are the point. No kit implements its own cooldown bookkeeping, combo counting,
or freeze logic — Ka-Chow, the spear stun and The World all call the same two classes. There is one
scheduler tick in `PowerSMP`, not four.

Two implementation notes worth knowing before editing:

- **Conditional buffs are re-derived, never undone.** Stance and daylight effects are applied with a
  60-tick duration and refreshed every second, so they lapse on their own when the condition stops
  holding. There is no "remove the old stance" code path to get wrong.
- **Attribute modifiers are the exception** — they persist until removed, including across restarts
  in player NBT. They are keyed (`Keys`), diffed against a cache so they are not re-sent every
  second, and explicitly stripped on join, quit and disable.

`Attributes` resolves attributes through the registry under both the 1.21.1 (`generic.max_health`)
and 1.21.3+ (`max_health`) spellings, so one jar covers the whole 1.21 line.

---

## Flagged mechanics — what was actually built

The spec flagged seven things as vanilla-impossible or needing a caveat. All defaults below follow
the spec's proposed fake unless noted.

**Made In Heaven** — does not touch tick rate. `/tick rate` is a single global server clock; there is
no per-entity tick rate, so speeding it up would speed the caster up too and slowing it down would
slow the server for players nowhere near the fight. Built as: caster gets Speed III + Haste II +
bonus attack speed + fall-damage immunity; everything else in radius gets Slowness II + Mining
Fatigue II. The relative speed difference — the part that's actually felt — is preserved.

**The World** — freezes everything in radius except the caster for exactly 9s. Mobs freeze cleanly
(AI off, velocity zeroed). Players get four things stacked: positional `PlayerMoveEvent` cancelled,
velocity pinned, Slowness 250 + a *negative* Jump Boost (this is what actually stops client-side
movement prediction, and is why it looks far less janky than move-cancelling alone), and their
actions cancelled — damage in and out, item use, block break/place, drops, projectiles, containers.

> Frozen players will still stutter, and can still look around, chat and swing their arm. That is
> client-side prediction, not a bug: the client moves first and asks permission after, and every
> rejection is a visible correction. Expect it to look rough on the receiving end.

**Mirage** — built on the **armour-stand fallback**, because adding ProtocolLib is
[open question #4](#open-questions) and nobody has approved a new dependency. Invisible armour
stands wearing the owner's player head with their nametag, lightly drifting, destructible. It is a
downgrade and the spec says so: at range the heads and nametags read as players, up close they are
obviously stands and they neither walk nor fight. `MirageProvider` is an interface — when the
dependency is approved, drop in a `ProtocolLibMirageProvider` and change one config line.

**Mushroom Hunger** — implemented as a stamping service, since nutrition and stack size are
per-`ItemStack` data components with no global switch. Stacks are stamped on first contact (join
scan, inventory open/click, pickup, craft, consume) and tagged with a hash of the current profile,
so a `kits.yml` change re-stamps on next contact instead of leaving stale values behind. Two
guardrails beyond the spec: a stack limit is only ever *raised*, and food values are only written to
materials that are actually edible.

`mushroom-hunger.scope` controls the flagged edge case:
- `OWNER_ONLY` *(default)* — only what Mavricc touches. Honest caveat: components travel with the
  stack, so stew handed to someone else keeps its buffed values.
- `GLOBAL` — also hooks hopper/dispenser movement and item spawns, at the cost of much more event
  traffic.

Raw mushrooms are **not** in the default list: they aren't edible in vanilla and already stack to
64, so including them would have been a nerf. Mushroom stew and suspicious stew are.

**Sporeic Wither Wings** — custom trigger, not advancement-based: `EntityDeathEvent` on a Wither,
killer checked for fungus in main hand, off hand or helmet at that moment. Grants a bound
unbreakable elytra (re-issued if it goes missing) plus a launch on cooldown.

**Ka-Chow** — a real `strikeLightning` plus `PotionEffectType.WITHER`, exactly as the spec notes.
`cosmetic-lightning-only: true` switches to a damage-free visual strike.

**Draconic Evolution** — stub. Picking up the dragon egg fires `DraconicEvolutionEvent` and logs it.
No power, because there is no design yet.

**Mace Massacre** — all vanilla-possible. The mace is soulbound via PDC: pulled out of death drops
and returned on respawn, undroppable, and blocked from any container inventory. If it is ever lost
anyway (void, lava, `/clear`), it is re-issued on join at the correct level, because the kill count
is mirrored in player data as well as on the item.

**`/xp` shadows a vanilla command.** `/xp` is vanilla's alias for `/experience`. Registering it here
takes over the unqualified name; vanilla stays reachable as `/minecraft:xp`, and this one as
`/powersmp:xp` or `/xpbottles`. Rename it in `plugin.yml` if that trade is not worth it.

**Long cooldowns now persist.** The 5-hour restock is written through to `data.yml` and rehydrated
on join. In-memory tracking is fine for a 10-second cooldown but not a 5-hour one, where a restart
or crash would have handed back a free use — `CooldownManager.registerPersistent` marks which
cooldowns get this treatment.

**Advancement keys used** (the corrected ones from the spec):
`minecraft:adventure/trade_at_world_height`, `minecraft:adventure/very_very_frightening`,
`minecraft:nether/all_biomes`.

---

## Decisions made where the spec left a gap

The spec listed numbers that "need a number from &lt;player&gt;". Rather than block the whole build on
four conversations, everything was built with a documented guess, and **every one of those numbers
lives in `kits.yml` marked `# ASSUMED`** — changing them is a config edit and a `/powersmp reload`,
not a rebuild. Replace them once the players weigh in.

| Gap | Decision | Where |
|---|---|---|
| Kit assignment (open Q1) | Hardcoded in `KitRegistry`, overridable via `assignments:` | `kits.yml` |
| Unlock gating (open Q2) | `unlock-all: true` — everything on. Thresholds are present but inert until you flip it | `progression:` |
| The World radius / cooldown | 8 blocks, 240s | `arhiahn.the-world` |
| Made In Heaven duration / cooldown | 20s / 240s, radius 12 | `arhiahn.made-in-heaven` |
| Requiem | **Disabled** — gated behind "if marb allows it" | `arhiahn.requiem.enabled` |
| Ka-Chow combo window | 3s, 3 hits | `kornflakis.ka-chow` |
| Overdrive damage semantics | Getting hit resets the sprint timer but does **not** strip an already-granted Strength II (the spec's recommended reading) | `damage-strips-tier2: false` |
| Lunge III→V deltas | pull 0.8/1.1/1.4, stun 3/4/5s; upgrades at 15 and 40 spear kills | `kornflakis.spear-master` |
| Spear hit cooldown | **8s — added, not in the spec.** A 5s stun on every single hit is unplayable at 0 | `hit-cooldown-seconds` |
| Flash trigger | `ON_HIT` (blinds what you hit), with a 5s internal cooldown so a combo can't chain-blind forever. `ON_ACTIVATE` is also built | `monkeyman.flash.mode` |
| Ambiguous "Strength I or II" | Strength **I** for both Power of the Sun and Mirage | `strength-amplifier` |
| Green stance knockback resistance | 1.0 (full immunity) — the spec named the perk but no number | `mavricc.stances.green` |
| Dimensional Adaptation scale/health | red 1.2/+6, blue 0.8/−4, green 1.4/+10 — entirely invented | `mavricc.dimensional-adaptation` |
| Mace levelling past kill 5 | `LADDER` — Density I–V, then Breach I–IV, then Wind Burst I–III. `LITERAL` keeps pumping Density past the vanilla cap | `techknight.mace.mode` |
| "every kill" | Mobs and players both count, mace must be in hand | `techknight.mace` |
| Restock contents | Placeholder list — "any utils I need" can't be built literally | `techknight.restock.items` |

Two judgement calls worth flagging explicitly:

- **`unlock-all` does not apply to trigger-gated powers.** Wither Wings and the three advancement
  powers always require their trigger. Open question #2 is about the tiered kits (KornFlakis,
  MonkeyMan4167); earning the achievement powers *is* the design, not a placeholder.
- **A spear stun leaves its victim hittable.** `FreezeUtil` blocks incoming damage for a time-stop
  (The World) but not for a combat stun — otherwise the stun would protect the person it lands on,
  which is backwards.

---

## Open questions

Still genuinely blocked on a human:

1. **Unlock gating** — what actually unlocks each tier for KornFlakis and MonkeyMan4167? Kills,
   playtime, admin grant? Kill-count scaffolding is built and inert.
2. **Mirage backend** — accept the armour-stand downgrade, or add ProtocolLib? The interface is
   ready either way.
3. **Draconic Evolution** — needs a design before it can be more than a stub.
4. **Requiem** — needs marb's yes, then flip `arhiahn.requiem.enabled`.
5. **The `# ASSUMED` numbers** — all guesses until the four players confirm them.

---

## Build order used

Shared infrastructure → Mavricc → KornFlakis → MonkeyMan4167 → arhiahn → achievement powers, as the
spec suggested. arhiahn last on purpose: those are the only powers that take control away from other
players, so they sit on freeze and cooldown infrastructure already exercised by lower-stakes kits.
