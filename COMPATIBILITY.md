# Server plugin setup

The plugin set for this server, and what each one needs configuring before PowerSMP works properly.

`softdepend` in `plugin.yml` fixes **load order** only — PowerSMP loads after ProtocolLib,
LuckPerms, NoCheatPlus and LaggRemoverPlus. It does not resolve any behavioural conflict below.

## Final plugin set

**Install these 11:**

| Jar | Role |
|---|---|
| `LuckPerms-Bukkit-5.5.53.jar` | permissions — also carries the NCP exemptions |
| `NoCheatPlus.jar` | anticheat — **needs exemptions, see below** |
| `LaggRemoverPlus.jar` | the chosen lag clearer — **needs exclusions, see below** |
| `InstaRestock-1.1.1.jar` | the newer of the two versions |
| `EZGaps-1.0.0.jar` | keep |
| `EzCobwebs.jar` | keep |
| `OnePlayerSleep.jar` | keep |
| `LimitedSpawns.jar` | keep — no conflict |
| `villagerinfinitetrading-1.0.jar` | keep — no conflict |
| `ProtocolLib.jar` | required for Mirage's real clones |
| `hidewhileinvisv1_1.jar` | obfuscates a player's tab list/chat name while they have Invisibility — **see §7, built for Astral Form** |
| `InvisDeaths1.0.0.jar` | obfuscates death messages naming an invisible killer or victim — **see §7, built for Astral Form** |

**Do not install these 3:**

| Jar | Why |
|---|---|
| `Clearlag.jar` | superseded by LaggRemoverPlus; three clearers is redundant and unpredictable |
| `LaggRemover-2.0.6.jar` | same — and it is the older sibling of LaggRemoverPlus |
| `InstaRestock-1.1.0.jar` | duplicate plugin; two copies fail to load or double-register listeners |

---

## 1. NoCheatPlus — do this first

Every kit moves players by setting velocity directly, which is exactly what NCP exists to stop.
Without exemptions, abilities get cancelled and players get flagged and kicked mid-fight.

Run **`server-setup/luckperms-commands.txt`** — it creates a `powersmp` group with the exemptions and
adds all 19 players. Paste it into console.

What breaks without it:

| Kit | Ability | What NCP sees |
|---|---|---|
| domanthegamer | Blood Chain | target velocity with no input |
| ItzMeTentx | dry riptide | a launch with no water — impossible in vanilla |
| Mavricc | Wither Wings launch, riptide | upward velocity, gliding start |
| Mavricc | blue stance / consolidation | extended reach via attributes NCP does not read |
| JJlionjxi | wind charges | repeated knockback |
| NorthOfNowhere | Made In Heaven | Speed III plus a velocity damper on nearby entities |
| NorthOfNowhere, xCR1T1Cx | The World, spear stun | cancelled movement, which reads as desync |
| Marb13_ | Haste V on deepslate | fast block breaking |
| MonkeyMan4167 | Mirage | packet entities NCP has no record of |

## 2. LaggRemoverPlus — exclude PowerSMP's entities and items

It removes ground items and non-player entities on a timer. Configure it to skip:

- **Named entities.** Mirage's armour-stand clones carry the owner's name — most clearers skip named
  entities by default, but confirm it. If clones vanish before their 12 seconds are up, this is why.
  *(The ProtocolLib backend is immune — those clones are packets, not entities, so no clearer sees
  them.)*
- **Named items on the ground.** Every bound item here has a custom name: the bound elytra, Spear of
  Momentum, Web Shooter, Cutlass Sword, Bloodlust Sword and Dragon Omelet. They land on
  the floor whenever an inventory is full.

> **The one that cannot be undone:** most bound items are re-issued on join if lost. **The Dragon
> Omelet is not** — it is granted exactly once, from the dragon egg. If a clearer sweeps it up,
> Mavricc loses Draconic Evolution permanently. If LaggRemoverPlus cannot exclude items by name, tell
> Mavricc to eat it immediately rather than carry it around.

## 3. InstaRestock — overlaps TechKnightGaming

Not a technical conflict, but it overlaps his Restock (`/power restock`: 7 configurable slots,
5-hour cooldown). If InstaRestock gives everyone restocking, his signature power stops being his.
Worth restricting InstaRestock to specific items, or accepting the overlap deliberately.

## 4. EZGaps — check for custom items

Mavricc's food rework exempts `GOLDEN_APPLE` and `ENCHANTED_GOLDEN_APPLE`, so normal gap crafting is
unaffected. If EZGaps adds *new* gap-like items under different material ids, those fall into the
"all other food" bucket and get bread-tier nutrition for Mavricc. Add them to
`mavricc.mushroom-hunger.other-food.exempt` if so.

## 6. OnePlayerSleep — a quiet buff to MonkeyMan4167

Power of the Sun keys off `world.isDayTime()`. With one-player sleep, nights end far more often, so
the power is active much more of the time. Not a bug, just worth knowing.

## 7. hidewhileinvis + InvisDeaths — built to complement Astral Form

Neither plugin knows PowerSMP exists. Both key off the plain vanilla `Invisibility` potion effect:
hidewhileinvis obfuscates a player's tab-list and chat name while they have it, InvisDeaths
obfuscates death messages that would otherwise name an invisible killer or victim. The Ghost's
Astral Form (`_glueman`) is built to trigger both — it applies the real `Invisibility` effect
alongside its own `hidePlayer`/`showPlayer` trick specifically so these two see it and act,
without PowerSMP calling into either plugin directly. No configuration needed; they just work once
installed. Astral Form removes the effect again the moment it ends, on death, or if he disconnects
mid-form, so nothing lingers past the ability itself.

No load-order requirement — order relative to PowerSMP does not matter for either.

## No conflict

**LimitedSpawns** caps natural mob spawns; PowerSMP spawns armour stands and lightning, neither of
which is a natural spawn. **villagerinfinitetrading** does not affect the `trade_at_world_height`
advancement that unlocks Sporic Mind Control — that fires on the trade itself.

---

## Order to do this in

1. Drop the 3 jars listed above; install the other 11.
2. Paste `server-setup/luckperms-commands.txt` into console.
3. Configure LaggRemoverPlus exclusions.
4. Start the server and check the log for `PowerSMP enabled with 20 kit(s).`
5. `/powersmp info <player>` for each of the 19 to confirm assignment and unlock state.
6. Test one movement ability per player — a dash, a grapple, a riptide — to confirm NCP is not
   blocking them.
7. Have `_glueman` toggle Astral Form near another player and check their tab-list name obfuscates,
   confirming hidewhileinvis is picking up the effect.
