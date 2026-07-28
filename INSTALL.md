# Installing

Two separate jobs: **building PowerSMP** (it does not exist as a jar yet) and **installing the other
plugins** (download and drop in).

---

## 1. Build PowerSMP

The repo is source only — there is no jar yet. Two ways to get one.

### Option A: let GitHub build it (no setup)

`.github/workflows/build.yml` builds the jar on every push and publishes it to a rolling
prerelease.

**Go to the repo's Releases page → "Latest dev build" → download `powersmp-0.1.0.jar`.**

No unzipping, and it always points at the newest successful build. You can force a rebuild with
**Actions → Build → Run workflow**.

The same jar is also attached to each Actions run under **Artifacts**, but that upload is
best-effort: Actions artifact storage is a hard account-wide quota, and once it fills the upload
fails even though the build succeeded. The release is the dependable route, which is why the
workflow marks the artifact step `continue-on-error`.

> If artifact storage is full and you want it back: **repo → Actions → any old run → delete**, or
> the account's billing/storage settings. Usage is only recalculated every 6–12 hours, so deletions
> do not free space immediately. The release route avoids the whole problem.

A green tick means it compiled. A red X on the **Build** step means a genuine compile error — send
the output. Note that a red X on the *artifact* step is just the quota and can be ignored; the jar
still made it to the release.

### Option B: build locally

Needs **JDK 21** and **Maven** (`java -version`, `mvn -version`):

```bash
git clone <this repo>
cd power-plugin
mvn package
```

The jar lands at `target/powersmp-0.1.0.jar`.

If it fails, it will be a Bukkit API mismatch. The *Version targeting* table in
[README.md](README.md) lists the likely spots in order.

---

## 2. Find the plugins folder

Where it is depends on how the server is hosted.

**Hosting panel** (Pterodactyl, Multicraft, Shockbyte, Apex, BisectHosting, etc.)
Open the **File Manager** in the panel. The `plugins` folder sits next to `server.jar`, `server.properties`
and the world folders. Upload jars there directly. Most panels also accept drag-and-drop, and
support SFTP if you would rather use FileZilla or WinSCP.

**Self-hosted**
`plugins/` sits in the same directory as the server jar you launch. If it does not exist yet, start
the server once and Paper will create it.

**Aternos / free hosts**
There is usually a *Plugins* section in the web panel with a search box. Search by name and install
from there. For anything not in their catalogue, look for an "upload" or "custom plugin" option —
some free hosts do not allow custom jars at all, which would block PowerSMP entirely.

---

## 3. Download the other plugins

Get these from official sources only — plugin jars from random reuploads are a common way servers
get backdoored.

| Plugin | Where |
|---|---|
| ProtocolLib | GitHub releases, `dmulloy2/ProtocolLib` — or its SpigotMC page |
| LuckPerms | `luckperms.net/download` → **Bukkit** (not Bungee/Velocity) |
| NoCheatPlus | its SpigotMC resource page or GitHub releases |
| LaggRemoverPlus | SpigotMC |
| InstaRestock **1.1.1** | SpigotMC — the newer of your two files |
| EZGaps | SpigotMC |
| EzCobwebs | SpigotMC |
| OnePlayerSleep | SpigotMC |
| LimitedSpawns | SpigotMC |
| villagerinfinitetrading | SpigotMC |

**Check each one supports 1.21.11** before installing. A plugin built for 1.20 may load and then
break in ways that look like PowerSMP bugs. NoCheatPlus in particular has historically lagged behind
new Minecraft versions — if there is no 1.21-compatible build, say so and the exemption work in
`server-setup/luckperms-commands.txt` becomes unnecessary.

Do **not** install `Clearlag.jar`, `LaggRemover-2.0.6.jar`, or `InstaRestock-1.1.0.jar` — see
[COMPATIBILITY.md](COMPATIBILITY.md).

**Also install `hidewhileinvisv1_1.jar` and `InvisDeaths1.0.0.jar`** — small custom jars, not from
the sources above; you already have them. Drop them in with the rest. They exist to complement The
Ghost's Astral Form (see [COMPATIBILITY.md §7](COMPATIBILITY.md)) — no configuration needed.

---

## 4. Install

1. **Stop the server.** Fully stop it — do not use `/reload`.
2. Copy all the jars into `plugins/`, including `powersmp-0.1.0.jar`.
3. **Start the server.**

> **Never use `/reload confirm` for this.** It re-initialises plugins without properly shutting them
> down, and with PowerSMP that risks leaving attribute modifiers stranded on players — permanently
> altered health, reach or armour that survives restarts. A full stop/start avoids it. (`/powersmp
> reload` is different and perfectly safe: it only re-reads `kits.yml`.)

Install order does not matter. `softdepend` in `plugin.yml` makes PowerSMP load after ProtocolLib,
LuckPerms, NoCheatPlus and LaggRemoverPlus automatically.

---

## 5. Verify

Check the startup log for:

```
[PowerSMP] Kit assignments loaded: 12 by name, 0 by UUID.
[PowerSMP] Mirage is using ProtocolLib: clones will be real player entities...
[PowerSMP] PowerSMP enabled with 12 kit(s).
```

If Mirage reports armour stands instead, ProtocolLib is missing or the wrong version — everything
else still works.

Then in game:

```
/plugins                     → all of them green, none red
/powersmp info Mavricc       → kit, stance, unlock state
```

---

## 6. Configure

1. Paste `server-setup/luckperms-commands.txt` into console. **Do this before anyone plays** — it
   creates the `powersmp` group with the NoCheatPlus exemptions and adds all 12 players. Without it,
   NCP cancels most abilities and kicks people mid-fight.
2. Configure LaggRemoverPlus to skip named entities and named ground items — see
   [COMPATIBILITY.md](COMPATIBILITY.md) for why the Dragon Omelet in particular matters.
3. Tune anything you like in `plugins/PowerSMP/kits.yml`, then `/powersmp reload`.

---

## Updating PowerSMP later

Push a change, download the new jar from Actions (or run `mvn package`), stop the server, replace
the jar, start. `plugins/PowerSMP/kits.yml` and `data.yml` are left alone — your tuning and
everyone's unlocks survive.
