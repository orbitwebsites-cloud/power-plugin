# PowerSMP

Bespoke per-player power kits for a Paper SMP. Every player has their own kit; nothing is shared
from a common ability pool.

- **Target:** Paper 1.21.11, Java 21, Maven — set `paper.api.version` in `pom.xml` to match the
  server exactly. `api-version` in `plugin.yml` is `'1.21'`, so the jar loads on any 1.21.x.
- **Build & install:** see [INSTALL.md](INSTALL.md). CI builds the jar on every push — grab it from
  **Releases → Latest dev build**, no local toolchain needed. Or `mvn package` locally.
- **Optional dependency:** ProtocolLib, for Mirage's real clones. Without it Mirage falls back to
  armour stands automatically; everything else is unaffected.
- **Configure:** `plugins/PowerSMP/kits.yml` (every tuning number lives there), `/powersmp reload`
- **Other server plugins:** see [COMPATIBILITY.md](COMPATIBILITY.md) — NoCheatPlus and the lag
  clearers both need configuration before this works properly

> **Compiles against Paper 1.21.11.** Verified by CI, which is where it first met the real Paper and
> ProtocolLib APIs — the environment it was written in could reach neither repository, so it was
> developed against a hand-written 119-type stub of the external API. That stub caught every
> internal wiring problem; the real compiler then caught exactly one thing the stub structurally
> could not, since the stub encoded the same wrong assumption. It is still untested *in game* —
> behaviour, balance and the ProtocolLib packet layouts all need a live server.

---

## Roster

| IGN | Kit | Powers |
|---|---|---|
| Mavricc | Mycelial | Stance Change, Mushroom Affinity, Mushroom Hunger, Sporeic Wither Wings, Dimensional Adaptation, Sporic Mind Control, Sporic of the Sea, Draconic Evolution |
| NorthOfNowhere | Stand User | The World, Made In Heaven, Requiem |
| xCR1T1Cx | Momentum | Ka-Chow, Overdrive, Spear Master |
| KornFlakis | Execution | `/kill`, 7-day cooldown |
| ItzMeTentx | Tidebound | Infinite Breathing & Dolphin's Grace, Faster Attack Speed, Trident God |
| domanthegamer | Spider | Spider Passive (low), Web Strike (mid), Web Shooter (high) |
| Sparkkkkkkkk | The Atom | Creeper Harvest (low), Explosion (mid), Atom Bomb (high) |
| Night_Scar3 | Mace Master | Permanent Strength (low), Dash (mid), Density Mace (high) |
| Marb13_ | Portal and Shadow Master | Miner's Haven (low), Ender Magic (mid), Portal & Shadow (high) |
| LlamaChas | Kryptonian | Flight, Heat Vision, X-Ray, Freeze Breath, Super Strength |
| JJlionjxi | Tempest | Wind God (low), Fat Tank (mid), Greedy Heal (high) |
| MonkeyMan4167 | Light | Flash, Power of the Sun, Mirage |
| TechKnightGaming | Mace Massacre | Mace Massacre, Restock, Infinite XP Bottles |

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
| `/kill <player>` | KornFlakis | Execute a player, 7-day cooldown |

Sneak + right-click with an empty hand fires your kit's primary ability
(`general.sneak-right-click-primary`).

---

## Version targeting

The 1.21 line moved a lot of API between 1.21.1 and 1.21.11, so this was written to survive it:

- **Attributes and enchantments go through the registry, not constants.** `Attribute` stopped being
  an enum in 1.21.2 and the keys lost their `generic.` prefix, so `Attributes` and `Enchants` try
  both spellings and degrade to a logged warning if one is genuinely missing. Referencing the
  constants directly would have been a link error on any later version.
- **Nutrition is applied to the eater, not baked into items.** `food` and `consumable` split apart in
  1.21.2; only the stack-size stamp still touches an item component.
- **Biomes are matched by key substring**, since `Biome` also stopped being an enum.

If a local build fails, these are the places to look first, in likelihood order:

| Symptom | Where | Fix |
|---|---|---|
| Mirage silently uses armour stands | `ProtocolLibMirageProvider` | Packet layouts moved — `ENTITY_TELEPORT` changed in 1.21.2 and `PLAYER_INFO` twice before that. The log names the exact field. Fails safe by design. |
| `PotionEffectType.X` / `Sound.X` unresolved | anywhere | Both became registry-backed later in the line; if the constants are gone, mirror the `Enchants` pattern. |
| `FoodComponent` method missing | `MushroomHungerService` | Only used in the non-default `ITEM_COMPONENT` mode; the default `CONSUME_TIME` path does not touch it. |

## Architecture

```
com.powersmp
├── PowerSMP.java          main class; owns the single shared 1/sec kit tick
├── kit/                   PowerKit interface, Ability, KitRegistry, impl/ (one class per player)
│                          one kit class per player (13), e.g. MavriccKit, LlamaChasKit
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

Only four things in this plugin are genuinely impossible. Everything else the spec flagged has a
real workaround, and the workaround is what ships.

**Actually impossible, no workaround exists:**

1. **Per-entity tick rate.** `/tick rate` is one global server clock. There is no API, NMS path or
   packet that gives one entity a different tick rate. Made In Heaven delivers the *effect* by other
   means; the mechanism is off the table.
2. ~~**A fake `Player` entity in pure Bukkit.**~~ **Solved** — ProtocolLib is approved and the real
   clone backend is built. Still true that *pure* Bukkit cannot do it; the armour-stand fallback
   remains for servers without ProtocolLib.
3. **Perfectly smooth player freezing.** The client predicts its own movement and is corrected
   afterwards. Every correction is visible. This is much smaller now (see below) but cannot reach
   zero.
4. **Global base food values.** Nutrition is a per-`ItemStack` data component; there is no server
   setting or datapack hook that rewrites what mushroom stew is worth for everyone. *(Worked around
   completely for per-player behaviour — see Mushroom Hunger.)*

Everything below is a workaround that was found and built.

**Made In Heaven** — the relative speed difference is what's actually felt, so that is what gets
built. Caster: **Speed III**, Haste II, bonus attack speed, fall-damage immunity. Other **players**
in range (mobs are left alone by default — `others-players-only`): Slowness II + Mining Fatigue II,
*plus* a per-tick velocity damper on anything non-living, excluding NorthOfNowhere's own projectiles.
That damper is the piece potions can't do — arrows, fireballs, TNT, minecarts and boats all crawl
too, which is what makes it read as the world slowing rather than as a debuff aura. The slow field
also re-applies to anyone who walks into the radius mid-cast, instead of only catching whoever was
standing there at the moment of the cast.

**The World** — freezes everything in radius except the caster for exactly 9s. Frozen targets can
never *deal* damage, and by default can still *take* it, so the stop is an opening to attack rather
than a shield around whoever got caught. `block-damage-to-frozen: true` restores the spec's literal
"cancel all damage dealt/received" reading. Mobs freeze cleanly
(AI off, velocity zeroed). Players get five things stacked:

- **Walk speed and fly speed set to zero.** This is the one that matters. It tells the *client* it
  cannot move, so it never predicts movement the server then has to reject — which is the actual
  cause of rubber-banding. Restored on unfreeze, and repaired on join if a crash left it at zero.
- Slowness 250 and a *negative* Jump Boost, pinning them to the ground.
- Velocity zeroed every tick, with a teleport-back safety net past one block of drift.
- Positional `PlayerMoveEvent` cancelled (look is still allowed).
- Actions cancelled: damage dealt, item use, block break/place, drops, projectiles, containers.

Non-living entities also get gravity switched off, so arrows and thrown potions hang in the air
instead of dropping, and primed TNT has its fuse held so it cannot detonate during the stop.

> Residual jank: frozen players can still look around, chat and swing their arm, and there may be
> slight stutter. Zeroing walk speed removes most of what the spec warned about, but client-side
> prediction cannot be fully eliminated.

**Mirage** — now has two backends, chosen by `monkeyman.mirage.provider`.

`PROTOCOLLIB` *(default)* — real clones. Packet-level player entities carrying MonkeyMan4167's
actual signed skin texture, which walk around and pop when swung at. Because they exist only on the
client, "attackable" is implemented by intercepting the attacker's `USE_ENTITY` packet and popping
the clone whose entity id was hit — so a decoy genuinely costs an attacker a swing. Requires the
ProtocolLib plugin; `softdepend` means the plugin still loads without it.

`ARMOR_STAND` — the zero-dependency fallback: invisible stands wearing a copy of the owner's armour
and held item under their player head, which renders as a player silhouette. They drift rather than
walk and are obvious up close. Equipment drop chances are forced to zero, or breaking a clone would
duplicate the owner's gear.

> **The ProtocolLib backend is the least verified code here.** Packet layouts are the least stable
> surface in the game — `PLAYER_INFO` changed shape in 1.19.3 and again in 1.20.2, entity teleport
> changed in 1.21.2 — and this was written against ProtocolLib 5.3.0 on 1.21.1 without being
> compiled or run. Every packet operation is wrapped: the first failure logs the offending field
> once, disables the backend for that session, and falls back to armour stands mid-cast rather than
> leaving invisible ghosts or spamming the console. Test Mirage in game before relying on it.

**Mushroom Hunger** — the spec's caveat about retroactively tagging every item in the world is
solved rather than mitigated. **Nutrition is applied to the eater, not the item.** Vanilla resolves
the meal, and one tick later the eater's food and saturation are overwritten with what this kit says
the meal was worth. Nothing is written to the stack, which kills the whole class of problem:

- No leak. Food handed to another player behaves normally for them — neither the stew buff nor the
  bread-tier nerf follows the item around.
- Every delivery route works, including the "obscure vectors" the spec flagged: hoppers, dispensers,
  villager trades, `/give`. The item never has to be seen beforehand, only eaten.
- Changing `kits.yml` takes effect on the next bite, with nothing stale in circulation.

`nutrition-mode: ITEM_COMPONENT` restores the old bake-into-the-item behaviour if it is ever wanted.

**Stack size is the one part that still has to be stamped onto items** — there is no per-player
equivalent of a stack limit — so that piece keeps the on-contact stamping (join scan, inventory
open/click, pickup, craft, consume, plus hopper and item-spawn hooks in `GLOBAL` scope), with a
profile hash so stacks are not reprocessed and a stack limit is only ever *raised*.

Raw mushrooms are **not** in the default list: they aren't edible in vanilla and already stack to
64, so including them would have been a nerf. Mushroom stew and suspicious stew are.

**Sporeic Wither Wings** — custom trigger, not advancement-based: `EntityDeathEvent` on a Wither,
killer checked for fungus in main hand, off hand or helmet at that moment. Grants a bound
unbreakable elytra (re-issued if it goes missing) plus a launch on cooldown.

**Ka-Chow** — a real `strikeLightning` plus `PotionEffectType.WITHER`, exactly as the spec notes.
`cosmetic-lightning-only: true` switches to a damage-free visual strike.

**X-ray** — genuinely impossible as asked: the client decides what it renders and no server-side API
reaches into that. Worked around by lying to one client. Every common stone-type block in radius is
sent to LlamaChas alone as air, leaving ores hanging in the open; the real world is never modified
and nobody else sees anything. The true blocks are re-sent when it expires, on quit, and on plugin
disable, so a disconnect cannot strand someone with a permanently wrong view. The hide list is
restricted to natural filler (stone, deepslate, dirt, gravel, the granites) so it can never blank out
a build. Radius is cubed, so 8 is ~4,900 blocks and 12 is ~15,600 — worth knowing before raising it.

**Flight** is ordinary creative flight, and is the single most anticheat-triggering thing in the
plugin. It is also handed back on quit and on disable: `allowFlight` persists in player data, so
skipping that would leave him flying in survival forever. Creative and spectator are left alone.

**Draconic Evolution** — no longer a stub. Picking up the dragon egg turns it into a single **Dragon
Omelet**; eating that is a one-way door with two effects.

*Stance Consolidation* — red, blue and green all run at once from then on. "The perks of all of
them" is read as the upsides only, so Strength II, Speed II, Haste II, Resistance II, both reach
bonuses and knockback immunity all apply, while red's armour penalty, blue's Weakness and green's
Slowness are dropped. `consolidation-includes-drawbacks: true` carries the costs over too.

Consolidation isn't just potion effects — every stance-gated power now reads "is this stance active"
rather than "is this stance selected", so all three branches of Sporic of the Sea fire together
(lightning crits, riptide, and permanent Conduit Power), and Dimensional Adaptation gets its own
`consolidated` size and health entry since no single stance applies any more.

*Weapon Modification* — a **Draconic Mace** that keeps Breach IV and loses the slam. Vanilla bakes
the fall-distance bonus into the mace's attack with no flag to disable it, so the exact vanilla slam
curve (+4/block for 3 blocks, +2 for the next 5, +1 after) is subtracted back out on hit. Subtracting
rather than capping matters: a cap would eat Strength and Breach along with the slam.

The omelet is handed out once and never re-issued; the mace is re-issued if lost, like the elytra.

**Trident God** — the only part of ItzMeTentx's kit that fights vanilla. Riptide is hard-gated on
being in water or rain: when dry, releasing a Riptide trident does nothing at all, *and* a Riptide
trident cannot be thrown either. So there is no vanilla behaviour to intercept or cancel — the dry
launch is re-implemented. Paper's `PlayerStopUsingItemEvent` reports how long the trident was
charged; held past vanilla's 10-tick charge while dry, the player is thrown along their look vector
on vanilla's own power curve.

The spin-attack stun stays hittable (`stunSeconds`, not `freezeSeconds`), so it is an opening rather
than a shield around whoever got hit. Because a manual dry riptide never sets vanilla's
`isRiptiding()` flag, the kit tracks its own 1.5s window and checks either.

Caveat: the dry launch reproduces the movement and the sound, not the spin *animation* — there is no
API to start a spin attack on 1.21.1.

**Execution (`/kill`)** — KornFlakis kills a named player outright, once every seven days. Two
details matter more than the effect itself:

- **`/kill` is a vanilla command admins use.** Claiming the name would take it from them, so anyone
  who is not KornFlakis is forwarded verbatim to `minecraft:kill` under their own permissions — ops
  keep the real command and its selectors. `/minecraft:kill` always works directly too.
- **The cooldown must persist**, and does. Seven days outlives any server uptime, so an in-memory
  timer would refund a free execution on the next restart or crash.

By default the kill bypasses everything — armour, Resistance, totems, and NorthOfNowhere's Requiem —
because that is what vanilla `/kill` does. `bypass-protections: false` routes it through the damage
system instead, so a totem can save the target. That is the only counterplay switch in the kit.

**Restock loadout GUI** — `/power loadout` opens a 7-slot menu (`slots`, up to 27) where
TechKnightGaming chooses what Restock hands him. The choice is saved per player, survives restarts,
and overrides the server default.

The menu never moves a real item. Every click is cancelled and handled by hand: clicking a stack in
your own inventory copies it in, clicking a filled slot clears it. That is deliberate — genuine
drag-and-drop would have to either keep the items, quietly charging him one of everything he
configures, or return them on close, which would duplicate whatever was pre-filled from the saved
loadout. Copying avoids both, so the menu can safely open showing the current kit. The amount you
click is the amount you get, so a stack of 16 pearls configures 16 pearls.

Loadouts are stored as Base64 of `ItemStack#serializeAsBytes`, not YAML's `ConfigurationSerializable`
path, which loses newer data components — so enchanted and custom-named gear round-trips intact.

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
| Unlock gating (open Q2) | `unlock-all: true` — everything on. Thresholds are present but inert until you flip it; nothing is set above 10 kills, so they stay reachable on an SMP | `progression:` |
| The World radius / cooldown | 8 blocks, 240s | `northofnowhere.the-world` |
| Made In Heaven duration / cooldown | 20s / 240s, radius 12 | `northofnowhere.made-in-heaven` |
| Requiem | **Enabled** — marb approved it. 2s damage immunity, 10min cooldown | `northofnowhere.requiem` |
| Ka-Chow combo window | 3s, 3 hits | `xcr1t1cx.ka-chow` |
| Overdrive damage semantics | Getting hit resets the sprint timer but does **not** strip an already-granted Strength II (the spec's recommended reading) | `damage-strips-tier2: false` |
| Lunge III→V deltas | pull 0.8/1.1/1.4, stun 3/4/5s; upgrades at 5 and 10 spear kills | `xcr1t1cx.spear-master` |
| Spear hit cooldown | **0** — every hit lunges and stuns, as specced | `hit-cooldown-seconds` |
| Flash trigger | `ON_HIT` (blinds what you hit), no internal cooldown. `ON_ACTIVATE` is also built | `monkeyman.flash.mode` |
| Ambiguous "Strength I or II" | Strength **II** for both Power of the Sun and Mirage | `strength-amplifier` |
| Green stance knockback resistance | 1.0 (full immunity) — the spec named the perk but no number | `mavricc.stances.green` |
| Dimensional Adaptation scale/health | red 1.2/+6, blue 0.8/+4, green 1.4/+10 — invented, no stance loses health | `mavricc.dimensional-adaptation` |
| Mace levelling past kill 5 | `LITERAL` — Density equals the kill count, past the vanilla cap, to the format ceiling of 255. `LADDER` (Density→Breach→Wind Burst) is the alternative | `techknight.mace.mode` |
| "every kill" | Mobs and players both count; the mace does not need to be in hand | `techknight.mace` |
| The World's damage rule | `block-damage-to-frozen: false` — frozen targets stay hittable, so the time-stop is an opening rather than a shield | `northofnowhere.the-world` |
| Restock contents | TechKnightGaming picks them himself in a 7-slot GUI (`/power loadout`); the config list is only a fallback until he does | `techknight.restock` |
| Greedy Heal durations / cooldown | 10s regen, 60s absorption, 90s cooldown — the amplifiers were specified, these were not | `jjlionjxi.greedy-heal` |

**These values are signed off.** They were guesses when written and have since been accepted as-is,
so they are the intended numbers rather than placeholders — but they all still live in `kits.yml`
and change with a `/powersmp reload`, no rebuild.

**No invented nerfs.** Where a value was ambiguous, the stronger reading wins: Strength II over I,
Density past the vanilla cap, no internal cooldown on the spear's stun or Flash's blind, no stance
that loses max health. The costs that remain are the ones the original design asked for — Red's
−2 armour bars, Blue's Weakness, Green's Slowness, and the bread-tier rework of non-mushroom food.

Two judgement calls worth flagging explicitly:

- **`unlock-all` does not apply to trigger-gated powers.** Wither Wings and the three advancement
  powers always require their trigger. Open question #2 is about the tiered kits (xCR1T1Cx,
  MonkeyMan4167); earning the achievement powers *is* the design, not a placeholder.
- **A spear stun leaves its victim hittable.** `FreezeUtil` blocks incoming damage for a time-stop
  (The World) but not for a combat stun — otherwise the stun would protect the person it lands on,
  which is backwards.

---

## Open questions

Still genuinely blocked on a human:

1. **Unlock gating** — what actually unlocks each tier for xCR1T1Cx and MonkeyMan4167? Kills,
   playtime, admin grant? Kill-count scaffolding is built and inert.
2. ~~**Mirage backend**~~ — resolved: ProtocolLib approved, real clones built. Needs an in-game test
   to confirm the packet layouts, since it could not be compiled here.
3. ~~**Draconic Evolution**~~ — resolved: Stance Consolidation via the Dragon Omelet, plus the
   slam-less Draconic Mace.
4. ~~**Requiem**~~ — resolved: marb approved it, and it is live.
5. ~~**The `# ASSUMED` numbers**~~ — accepted as-is. Still tunable in `kits.yml` at any time.
6. ~~**KornFlakis has no kit**~~ — resolved: Ka-Chow, Overdrive and Spear Master belong to
   **xCR1T1Cx**; KornFlakis has Execution (`/kill`, 7-day cooldown).

---

## Build order used

Shared infrastructure → Mavricc → xCR1T1Cx → MonkeyMan4167 → NorthOfNowhere → achievement powers, as the
spec suggested. NorthOfNowhere last on purpose: those are the only powers that take control away from other
players, so they sit on freeze and cooldown infrastructure already exercised by lower-stakes kits.
