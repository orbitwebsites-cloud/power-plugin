# Server plugin compatibility

The other plugins on this server, and where they collide with PowerSMP.

`softdepend` in `plugin.yml` only fixes **load order** — it makes PowerSMP load after these so
permissions and packet hooks are ready. It does not resolve any behavioural conflict below. Two of
these need real configuration work before the SMP goes live.

---

## 🔴 NoCheatPlus — will break a lot of the plugin

This is the big one. PowerSMP moves players by setting velocity directly, and NCP is built to stop
exactly that. Expect these to be blocked, rubber-banded, or to get the player flagged and kicked:

| Kit | Ability | What NCP sees |
|---|---|---|
| domanthegamer | Web Shooter grapple | large sudden velocity, mid-air |
| domanthegamer | Web pull | *other* players yanked without input |
| domanthegamer | Wall climbing | vertical movement with no ladder |
| Night_Scar3 | Dash | burst velocity in any direction |
| ItzMeTentx | Dry riptide | launch with no water, which is impossible in vanilla |
| Mavricc | Wither Wings launch | upward velocity + gliding start |
| Mavricc | Sporic Riptide | in-water launch |
| JJlionjxi | Wind God | wind-charge knockback, repeatedly |
| NorthOfNowhere | Made In Heaven | Speed III plus a velocity damper on nearby entities |
| NorthOfNowhere / xCR1T1Cx | The World / spear stun | movement cancelled server-side, which reads as a client desync |
| MonkeyMan4167 | Mirage | ProtocolLib packet entities NCP does not know about |

**What to do:** give every kit owner an NCP exemption for the movement checks — at minimum
`nocheatplus.checks.moving.survivalfly`, `.creativefly`, `.morepackets`, and
`nocheatplus.checks.fight.reach` (Mavricc's blue stance and consolidation both extend reach via
attributes, which NCP does not read).

Cleanest route with LuckPerms:

```
/lp creategroup powersmp
/lp group powersmp permission set nocheatplus.checks.moving true
/lp group powersmp permission set nocheatplus.checks.fight.reach true
/lp group powersmp permission set powersmp.use true
/lp user <each IGN> parent add powersmp
```

Worth testing one ability per player before opening the server. A false kick during a fight is worse
than the ability simply not existing.

## 🔴 ClearLag / LaggRemover / LaggRemoverPlus — will delete PowerSMP entities

Three lag clearers is already redundant; running all three makes the behaviour hard to predict. They
remove ground items and non-player entities on a timer, which hits:

- **Mirage armour-stand clones.** They carry a custom name, which most clearers skip by default —
  verify that. If clones vanish early, this is why. (The ProtocolLib backend is immune: those clones
  are packets, not entities, so no clearer can see them.)
- **Dropped kit items.** The bound elytra, Spear of Momentum, Web Shooter, soulbound maces and the
  Dragon Omelet all drop to the floor when an inventory is full. A clearer sweeping ground items can
  destroy them. Most are re-issued automatically on join — **the Dragon Omelet is not.** It is
  granted exactly once, so if a clearer eats it, Mavricc loses Draconic Evolution permanently.
- **Shadow items** dropped on the floor, though those are meant to expire anyway.

**What to do:** pick one lag clearer and remove the other two. Exclude armour stands and named
entities. If possible, exclude items with custom names, which covers every bound item here.

## 🟡 InstaRestock — two versions, and it overlaps TechKnightGaming

`InstaRestock-1.1.0.jar` and `InstaRestock-1.1.1.jar` are both in the list. **Only load one** —
two copies of the same plugin will either fail to load or double-register listeners.

It also overlaps TechKnightGaming's Restock (`/power restock`, 7 slots, 5-hour cooldown). Not a
technical conflict, but if InstaRestock gives everyone restocking, his signature power stops being
his.

## 🟡 EzCobwebs — overlaps Web Strike

domanthegamer's Web Strike places cobwebs and restores the original blocks after 60s. If EzCobwebs
also manages cobweb placement or breaking, the two may fight over the same blocks. PowerSMP only
reverts blocks that are *still* cobweb when the timer ends, so anything EzCobwebs changed first is
left alone — no block duplication, but webs may disappear early.

## 🟡 EZGaps — interacts with Mushroom Hunger

Mavricc's food rework exempts `GOLDEN_APPLE` and `ENCHANTED_GOLDEN_APPLE`, so custom gap crafting is
unaffected. But if EZGaps adds *new* gap-like items under different material ids, those fall into the
"all other food" bucket and get bread-tier nutrition for Mavricc. Add them to
`mavricc.mushroom-hunger.other-food.exempt` if so.

## 🟡 OnePlayerSleep — interacts with Power of the Sun

MonkeyMan4167's Power of the Sun keys off `world.isDayTime()`. With one-player sleep, nights end far
more often, so the power is effectively active much more of the time. Not a bug — worth knowing it
is a straight buff to him.

## 🟢 LuckPerms — no conflict, and needed

Manages `powersmp.use` (default true) and `powersmp.admin` (default op). PowerSMP `softdepend`s on it
so it loads first. This is also the cleanest way to hand out the NCP exemptions above.

## 🟢 LimitedSpawns — no conflict

Caps mob spawns. PowerSMP spawns armour stands (Mirage) and lightning (Ka-Chow), neither of which is
a natural mob spawn.

## 🟢 villagerinfinitetrading — no conflict

Mavricc's Sporic Mind Control unlocks from the `trade_at_world_height` advancement, which fires on
the trade itself regardless of stock limits.

---

## Load order

`softdepend` in `plugin.yml` lists ProtocolLib, LuckPerms, NoCheatPlus and the three lag clearers, so
PowerSMP loads after all of them. Soft, so any that are absent are simply skipped.
