# CLAUDE.md

Guidance for Claude Code when working in this repository. This file in the repo root is the
canonical copy; the operator's workspace keeps an identical copy one directory up
(`..\CLAUDE.md`) so it loads at session start — update both together.

## What this is

CustomDaytime is a Paper/Sponge Minecraft plugin that customizes day/night cycle length (day and
night configured independently, in minutes) and replaces sleep-to-skip-night with a smooth time
acceleration when enough players sleep.

This repo is a **fork** (`github.com/xbadc0ffe/CustomDaytime`) of upstream `SeedimV/CustomDaytime`
(GPL-3.0). The fork adds **corrections** — fixes for vanilla mechanics that break when the day/night
cycle is stretched. Keep the diff against upstream **minimal and localized** so upstream bug-fixes
merge without conflicts. **Preserve the GPL-3.0 license and upstream attribution**: every source
file carries the `Copyright (c) 2026 Seedim` GPL header — copy it into any new file.

## Where things stand (2026-10-15)

Release **2.1.3** is the current build: the 2026-10-13 redesign (vanilla NIGHT_SKIP as the sleep
signal, anchor-based `villagerSleep`, `Villager#restock()`-based `villagerStock`) with `debug=false`.
It was proven on two days of debug logs from the 2.1.2 debug build (see *Verification status*).
Debug was then switched off; the next reported issue starts a new debug session — apply the
log-volume cuts under *Debug logging* **before** shipping that debug build.

## The two-clock model (the key mental model)

Minecraft has two clocks:

- **dayTime** — the day/night clock, Bukkit `World.getFullTime()`. This plugin **stretches** it. It
  is owned exclusively by `WorldTimeController`.
- **gameTime** — a monotonic tick counter, Bukkit `World.getGameTime()`, unaffected by stretching.

Most mechanics run on gameTime and are fine. Bugs occur where a mechanic judges a gameTime value
against a threshold calibrated to the vanilla day length (24000 ticks) — when the day is stretched,
that threshold no longer matches the cycle.

**INVARIANT: every correction reads `world.getGameTime()`, never `getFullTime()`.** Vanilla
timestamps (`LAST_SLEPT`, `LAST_WORKED_AT_POI`, `TIME_SINCE_REST`, restock times) are gameTime
values. Never put a dayTime value on one side of a comparison against a gameTime value — that mixing
*is* the bug being fixed.

## Build & test

Gradle (Kotlin DSL), wrapper `./gradlew` (`gradlew.bat` on Windows/PowerShell). The paper module
targets **Java 25** (`options.release.set(25)`); Gradle toolchain auto-download provisions Temurin 25
automatically — no manual JDK install needed.

The paper module compiles against **paper-api 26.3** (`paper = "26.3.build.+"` in
`gradle/libs.versions.toml`, `runServer` on 26.3) but still declares `apiVersion = "26.1.2"`: every
API the plugin uses (`Villager#restock()`, `Player#isSleepingIgnored()`, `ClockTimeSkipEvent`) exists
in 26.1.2, so the jar loads on 26.1.2+. Bump `apiVersion` only when a newer-only API is introduced.

- **Green gate — use this: `./gradlew :paper:build`.** Produces
  `paper/build/libs/CustomDaytimePaper-<version>.jar` (the deployable jar). Keep it green at every
  commit.
- The full `./gradlew build` currently **fails on the `:sponge` module** locally: VanillaGradle can't
  resolve `net.minecraft:joined:<mcver>` (a pre-existing upstream/environment issue, not plugin
  code). Don't chase it locally — use `:paper:build`. Corrections are paper-only, so a `common`
  change that compiles keeps sponge valid.
- `.github/workflows/ci.yml` runs the full `./gradlew build` (Temurin 25, ubuntu-latest) on every
  push to `master`, the only branch. The Minecraft artifact resolves there, so this is the only
  gate that actually compiles `:sponge` — check it after any `common` change.

### Release hygiene (binding, user instruction 2026-10-14)

- **Every build with a code change bumps `version`** in the root `build.gradle.kts` (2.1.3 now).
  The jar name is how the operator tells builds apart on the server; a changed build under an
  unchanged name is indistinguishable from the one already deployed.
- **A debug build is named `CustomDaytimePaper-<version>_debug.jar` and ships `debug=true`** in the
  bundled `common/src/main/resources/config.conf`; a release ships `debug=false`. The bundled
  config is only written on first install — an existing server `config.conf` keeps its own
  `debug` value — so the handover must say which value the server config needs, and must state
  the jar name and version explicitly.
- **Never push, open a PR or trigger CI until the user has tested the build and said so.** Commit
  locally on `master` in the meantime.

## Modules

Dependency flow `paper`/`sponge` → `common` → `api`. Package root `xyz.mayahive.customdaytime`.

- `api` — platform-agnostic interfaces/models (`Platform`, `PlatformWorld`, `PlatformScheduler`,
  `WorldKey`). No Lombok, no platform deps.
- `common` — all platform-agnostic logic: bootstrap, context, event bus, services (config +
  migration, update checker), `WorldTimeController`/`WorldTimeScale`, the correction SPI. Bundles
  configurate-hocon.
- `paper` — Paper/Bukkit/Folia impl; `shadowJar` → the deployable jar. **Correction implementations
  live here.**
- `sponge` — Sponge impl, parallel to paper. **Corrections are paper-only; sponge registers none and
  must compile untouched.** Do not add sponge code for corrections.

## How time advances

Per-world `WorldTimeController` (common), one per configured+loaded world, managed by
`WorldTimeManager`. A 1-tick repeating task (Folia global-region scheduler) reads `getFullTime()`,
advances it by a fractional per-tick increment sized so a 12000-tick half-cycle spans the configured
minutes, and writes absolute time. Sleep acceleration multiplies the night increment.

**Clock writes are not free.** In 26.x `CraftWorld.setFullTime` fires a CUSTOM `TimeSkipEvent`
synchronously and then `ServerClockManager.setTotalTicks` broadcasts a `ClientboundSetTimePacket` to
every player in the world — on every call. The controller writes whenever its fractional carry
crosses a whole tick (every 3rd tick at 30/30, every tick while accelerating). The user has accepted
this cost; do not "optimize" the write cadence without asking. `PaperWorld.time(long)` sets a
thread-local flag around the write so `TimeSkipListener` can tell the plugin's own CUSTOM events
from foreign time changes (commands, other plugins), which it logs in debug mode.

**Saw-tooth (known, harmless, upstream behaviour):** between the controller's writes vanilla still
adds +1/tick, so at 30/30 the clock oscillates ±2–3 ticks around the target and each half-cycle
boundary is detected — and the config reloaded — three times. A hysteresis on the cycle-change
check would remove the triple log line; nothing else depends on it.

**Sleep acceleration decision** (`WorldTimeController.shouldAccelerate`): night + acceleration
enabled + (**vanilla sleep signal fresh** OR plugin counts say enough sleepers). The vanilla signal
is the NIGHT_SKIP `TimeSkipEvent`: the server fires it *every tick* while its `SleepStatus` says
enough players are deep-sleeping, and `TimeSkipListener` cancels it on governed nights and forwards
it as `WorldTimeManager.vanillaSleepSignal(key)`; it goes stale 2 controller ticks after the last
event. The plugin's own counts exist only so acceleration starts the moment players lie down (vanilla
needs 100 ticks of deep sleep) and they mirror `SleepStatus`: spectators excluded, `isSleepingIgnored`
players count as sleeping, needed = `max(1, ceil(players × pct / 100))`, at least one real sleeper.
Never re-derive vanilla's verdict to gate the cancel — that divergence was the "sleep sometimes
does nothing" bug. Citizens NPCs and AFK-plugin players show up in these counts exactly as they do
in vanilla's `SleepStatus` (NPCs as sleeping-ignored "players").

**Daytime sleep (thunderstorm):** vanilla allows it and fires NIGHT_SKIP after 100 ticks; on a
governed world the listener answers SUPPRESS_JUMP (`setSkipAmount(0)`): the sleeper is woken, the
storm is cleared, the clock does not jump to the next morning. Accelerating *through* the storm
instead was discussed and never approved — not implemented.

`WorldTimeScale` (common) is the **single source** of the stretch factor. From the configured
day/night minutes it exposes `dayScale`/`nightScale`/`cycleScale` (= configured length ÷ vanilla
length; vanilla day = vanilla night = 10 min) and the day/night increments (`= 1/scale`). The
`12000` half-cycle constant lives only here. Corrections read the **current** scale via a live
`Supplier<WorldTimeScale>` (backed by `WorldTimeManager.scale(key)`), never a captured snapshot, so a
scale change is reflected without re-enabling.

## Corrections

Per-world config toggle under a `corrections { }` block. Default ON = genuine bugs; default OFF =
rate preferences (already correct in real time; enabling makes the server easier).

| Toggle | Default | What it does |
|---|---|---|
| `villagerSleep` | on | Anchor-based: remembers each villager's last *real* sleep (PDC `sleep_anchor`/`sleep_written`) and rewrites `LAST_SLEPT` so vanilla's gap reads `(now − realSleep)/cycleScale`; eligibility lasts exactly one stretched day per real sleep, missed sweeps are caught up in full. |
| `villagerStock` | on | Drives vanilla's own `Villager#restock()` every 12000 real ticks, around the clock, for villagers with a `JOB_SITE` that reached it within the last **stretched** day (`24000 × cycleScale`). |
| `phantomSpawn` | on | Slows `TIME_SINCE_REST` growth to 1/cycleScale (rewrites the stat — its display becomes inaccurate). |
| `pillagerSpawn` | off | Probabilistically cancels `PATROL` spawns (whole patrols) to thin them out. |
| `weatherTimer` | off | Scales rain/thunder/clear durations by cycleScale. |
| `wanderingTraderSpawn` | off | **Startup log-warning only** if `paper-world-defaults.yml` `spawn-day-length` ≠ 24000×cycleScale. No spawn logic. |

`VILLAGER_SCHEDULE` was investigated and **intentionally not implemented**: no confirmable
stretching-sensitive read of `LAST_WORKED_AT_POI` exists in vanilla (the schedule is dayTime-based
and unbroken; golem eligibility uses `LAST_SLEPT`; restock is `villagerStock`'s domain). Revisit only
if a real gate is found in source.

### Correction SPI (paper class hierarchy)

- `Correction` (common) — `key()`, `defaultEnabled()`, `enable(WorldKey, Supplier<WorldTimeScale>)`,
  `disable(WorldKey)`. `CorrectionRegistry` (common) enables them per world from config and has
  **zero default registrations** — platform modules register their own in `CustomDaytimePaper`.
- `AbstractScheduledCorrection` (paper) — owns the ~20s (400-tick) global sweep, per-world
  scale/baseline state, and hands subclasses a `WorldSweep(now, delta, cycleScale)` where
  `delta = elapsed*(1 - 1/cycleScale)`. The sweep baseline (`lastSweepGameTime`) advances
  **unconditionally** once the world resolves — before the no-scale / cycleScale≤1 guards — so a
  run of skipped sweeps can't inflate a later `elapsed`. No-op at `cycleScale ≤ 1`.
- `TrackedVillagerCorrection` (paper) — maintains a per-world villager set from
  `EntityAddToWorldEvent`/`EntityRemoveFromWorldEvent`, seeded from loaded chunks on enable, and
  dispatches `applyToVillager(Villager, WorldSweep)` per villager via `entity.getScheduler()`.
  Subclasses re-read `world.getGameTime()` on the villager's thread (the task may run later than
  the sweep). Subclasses: `VillagerSleepCorrection` (absolute target from a PDC anchor, never an
  increment on the previous write), `VillagerStockCorrection`. Each subclass instance has its own
  tracked set and is registered as a listener separately in `CustomDaytimePaper`.
- `PhantomSpawnCorrection` extends `AbstractScheduledCorrection` directly (player-shaped, enumerates
  `Bukkit.getOnlinePlayers()`, no tracked set). `PillagerPatrolCorrection` / `WeatherTimerCorrection`
  implement `Correction` + `Listener`; `WanderingTraderSpawnCorrection` implements `Correction`
  (startup check only). `WorldKeys` is the shared `World`↔`WorldKey` helper.

### `villagerStock` gate order (why it can be silent)

`JOB_SITE == null` → return silently · no recipe with `getUses() > 0` → return silently ·
`LAST_WORKED_AT_POI` null or older than `24000 × cycleScale` → one-shot "held" debug line ·
PDC `last_driven_restock` younger than 12000 ticks → return silently · else `restock()` + a
"restocked" debug line. Vanilla itself restocks a villager standing at its workstation during the
work window (dayTime 2000–9000, 17.5 real min at 30/30) within ~15 s of a trade, so a daytime test
cannot tell the plugin from vanilla — **test restocking by locking a trade at night.** Without the
correction, stretched vanilla restocks only in that window, so a trade locked just after it waits
up to ~42 real minutes; with the correction the wait is ≤10 min at any hour.

### Known side effects

- `VillagerStockCorrection` calls `Villager#getRecipes()` every sweep for every tracked villager.
  For an employed villager nobody has traded with yet this makes vanilla generate its offers up to
  20 s before the first trade click would (same RNG roll, different moment; a profession reset
  still nulls the offers, so trade cycling is unaffected). `VillagerAcquireTradeEvent` listeners see
  the generation at sweep time.
- `VillagerSleepCorrection` writes `LAST_SLEPT` on every villager that has ever slept, every sweep
  (hundreds of brain writes per 20 s on a big farm — cheap, but it is there).

## Verification status (2.1.2 debug build, logs of 2026-10-14 and 2026-10-15)

- **Sleep acceleration: confirmed.** 7 of 7 player sleeps accelerated to morning (dayTime
  ≈23530–23571 at stop); NIGHT_SKIP cancelled every tick on governed nights; zero foreign or
  external time changes; one daytime-thunderstorm sleep handled as SUPPRESS_JUMP exactly as
  designed.
- **`villagerSleep`: confirmed.** A ~230-villager iron farm (192 with beds, re-sleeping every ~30 s
  all night as the zombie cycles past, never by day) stayed golem-eligible around the clock: the
  written vanilla gap peaked at 23 7xx just before the next dusk, never 24000. The only outage was
  the farm's chunk being unloaded during a night that a player then skipped — no sleep that cycle,
  eligibility ran out an hour after the last one; vanilla would do the same.
- **`villagerStock`: unverified.** Zero lines in 29 h: every sweep exited silently (no `JOB_SITE`
  or no used trade) because nobody traded enough. The scaled gate and `Villager#restock()` are in
  the build; a deliberate night test with reason-logging is still owed if restock gaps are
  reported again.
- **`phantomSpawn`, `pillagerSpawn`, `weatherTimer`, `wanderingTraderSpawn`: not exercised in logs.**
- **Bedrock bed kick:** none seen after the Geyser/Floodgate update (see *Known external issues*).

## Debug logging

Top-level `debug=true` in `config.conf` (release default `false`; an existing server config keeps
its own value). What is logged:

- Controller: acceleration start/stop (with counts and the vanilla-signal state), sleep-count
  updates, NIGHT_SKIP bursts (first event of a sleep, then every 100th), foreign time changes
  (calling class, at most one per 100 ticks per world), external-time resyncs (one per 200 ticks),
  cycle-change + config-reload lines.
- `villagerSleep`: one `sweep` line per world per sweep (gameTime, dayTime, cycleScale, tracked
  count); per villager per sweep either a gap line (`sinceSleep=a/b vanillaGap=x->y wrote
  lastSlept=…` or `no-op`) or `skip: no LAST_SLEPT (never slept)`; `RE-ANCHORED` on every real
  sleep; `LAPSED` (once per tracking, re-logged after a chunk reload) / `RE-ENGAGED`;
  `TRACKED`/`UNTRACKED` with the chunk on every entity add/remove.
- `villagerStock`: one line per driven restock, one when a villager is held by the work gate.
  **Nothing** when a villager has no `JOB_SITE` or no used trade — silence is ambiguous.

There is **no per-tick log line** — do not add one. But the per-sweep lines are NOT cheap either:
**measured 102 MB / 730k lines in 18 h** on a world with ~300 tracked villagers (2026-10-15):
398k gap lines, 166k never-slept, 112k RE-ANCHORED, 45k TRACKED/UNTRACKED, 3k sweep lines.

**Required before the next debug build:** gap line only every 10th sweep per villager or when the
written gap exceeds 20000; never-slept one-shot per tracking; RE-ANCHORED only when the previous
anchor is >1200 ticks old (count the rest in the sweep line); TRACKED/UNTRACKED as per-sweep
counts, not per entity; `villagerStock` logs *why* it skipped (no job site / nothing used / held)
one-shot per villager plus a per-sweep summary (tracked / employed / depleted / held / restocked);
optionally a per-sweep farm line (eligible / golem-detected / sleeping-now counts) so a farm
complaint can be read without reconstructing it from gap lines.

### Reading a debug log

- Locate a villager group by the chunk in its `TRACKED` lines; the first-seen time is when a player
  loaded it, and `TRACKED`/`UNTRACKED` bursts of ~200 are a player teleporting to/from a farm.
  Player commands (`issued server command`) and login positions in the server log give the
  timeline.
- A `LAPSED` on first sight after a chunk load is stale state, not a failure. A real failure would
  be a `LAPSED` on a villager that was loaded and sleeping — never seen.
- A few villagers lapse for ≤2 min at each dusk (their last sleep of the previous night was at its
  start); vanilla has the same edge. A whole farm lapsing means its chunk was unloaded while
  someone skipped the night.
- Rate: at 30/30, night is the first 30 min of each hour (shifts after every skipped night). A
  player sleep compresses the rest of the night into seconds, which also ends villagers' REST
  schedule — villagers that have not slept yet get no sleep that cycle.
- `villagerStock` silence must be resolved by the gate logging above or by `/data get entity
  @e[type=villager,limit=1,sort=nearest] Brain.memories` on a locked villager (`minecraft:job_site`,
  `minecraft:last_worked_at_poi`).

## Known external issues (not plugin bugs)

- **Bedrock players kicked for "sending too many packets" when using a bed** on Paper 26.3 is
  Geyser bug #6752 (reported on a server without this plugin, closed 2026-10-12 as fixed in the
  latest Geyser preview build). Update Geyser-Spigot/Floodgate before suspecting this plugin.
- `/time query daytime` fails since 26.2 (timeline clocks). Measure with `getFullTime`/`getGameTime`.
- **Entity add/remove storms** in the debug log — the same villagers `TRACKED`/`UNTRACKED` many
  times per second, "in" for exactly one tick, a whole chunk's villagers flipping together — are
  another plugin sync-loading an unloaded chunk one tick at a time (the server saves it each
  cycle). Seen 2026-10-15 at one base. Not this plugin; find what polls that chunk.

## Config & migration

`config.conf` (HOCON, configurate). Per-world blocks keyed by full world key (`"minecraft:overworld"`).
Loaded once into an in-memory node at `ConfigService` construction.

Startup migration (`ConfigService.migrateIfNeeded`) is guarded by a top-level `schemaVersion`
(current = `SCHEMA_VERSION`). If the config is below it: back up `config.conf` → `config_prev.conf`
(never clobbering an existing backup), add the `corrections { }` block per world with commented
defaults **preserving existing user values**, set `schemaVersion`, save. Idempotent — the version
guard makes a second run a no-op. The shipped default ships at the current `schemaVersion` so fresh
installs skip migration. Comments are written via `CommentedConfigurationNode`. INFO logs on
migration and, in `CorrectionRegistry`, on enable (active corrections per world).

## Conventions

- **Lombok, fluent accessors** (`lombok.config`: `accessors.fluent=true`, `chain=false`). Accessors
  are `foo()` / `foo(x)`, not `getFoo()` / `setFoo()`. Applied to all modules except `api`.
- **No per-tick full-world entity scans.** Entity corrections use a tracked set + a ~20s sweep and
  **measured elapsed gameTime** (`now - lastSweepGameTime`), never a nominal interval.
- **Folia support is preserved** (`foliaSupported=true`). All entity/player state access goes through
  `entity.getScheduler()`; world-global reads (game time, weather) run on the global-region
  scheduler. Never bulk-query world entities off the owning region thread — seed via
  `getLoadedChunks()` + the region scheduler.
- **Prefer Bukkit/Paper API over NMS.** All current corrections are pure Bukkit; flag and justify any
  NMS before using it.
- Keep the diff against upstream minimal and localized.
- Verifying server behaviour: download the Paper build from
  `https://fill.papermc.io/v3/projects/paper/versions/<ver>/builds/latest`, run it with
  `-Dpaperclip.patchonly=true`, and decompile the wanted classes from
  `versions/<ver>/paper-<ver>.jar` with CFR. Verified 26.3 facts (tick order, sleep block,
  `SleepStatus`, golem/restock gates, timeline keyframes) are summarised in the session memory.

## Git

Single branch: **`master`**, on the fork `origin` (`github.com/xbadc0ffe/CustomDaytime`). All work
commits there directly; there is no separate integration or feature branch, and none should be
created. The old `daytime-corrections` branch was fast-forwarded into `master` and deleted.

As of 2026-10-15 everything through the 2.1.3 release is pushed to `origin/master` (the 2026-10-13
redesign `2e977c1` + `4561377` + `2c75f03`, then the 2.1.3 release commit). CI runs the full build
(incl. `:sponge`) on each push — check it after pushing.

To pull upstream fixes, add an `upstream` remote for `SeedimV/CustomDaytime` and merge into
`master`. There is no longer a clean upstream-tracking branch, so expect to resolve fork-vs-upstream
conflicts in that merge rather than having them isolated.
