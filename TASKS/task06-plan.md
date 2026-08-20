TASK 06 — PLAN (Environment Rules + Resist Theme in Sim)
=========================================================

Research
--------
Verified against the current codebase (task05 landed: `RunContext` carries a
snapshotted `resistTheme: ResistTheme` + `roster`, `RunGen.roll/tutorial` fill
it, `Sim.initial` has `Long`/`RunContext` overloads; `sbt compile`/`sbt test`
pass, 30 tests):

- **`Enemy`** (`Model.scala:42-63`) stores no effective resist today:
  `def resist: Resistances = spec.resist` (`:62`) re-resolves the base resist
  from config on every read. The one and only construction site is
  `Sim.spawn` (`Sim.scala:74-82`), which currently omits resist entirely.
  `Enemy.resist` is only *read* inside `Sim.moveProjectiles`
  (`Sim.scala:273,276,282`); the renderer never touches it (grep confirms).
- **`Resistances`** (`Model.scala:8-20`) is a 5-field `Float` case class with an
  `apply(kind)` lookup. `ResistTheme` (`GameConfig.scala:112-117`) holds
  `primary/secondary: DamageKind` + `primaryAmount/secondaryAmount: Float`.
- **`World`** (`Model.scala:103-121`) has `won/kills/leaks` as its only
  defaulted fields; `Sim.initial` is the only constructor. `lives` is an `Int`;
  `completeLevel` (`Sim.scala:118-139`) carries lives/gold over but clears
  towers/enemies/shots.
- **Environment rules** already exist as data (`config.json:99-128`) via
  `Biome.environment: Option[Effect]` (`Effect(kind: String, params:
  Map[String, Float])`, task03). Kinds in config: `openFloorPlan`,
  `crushed`, `recycling`; tutorial has `"environment": null`.
- **Aura radius is authored in cells** and multiplied by `cellPx` in the loader
  (`ConfigLoader.scala:160`). `Effect.params` are raw floats (no unit
  conversion at load), so Sim must convert any cell-based env radius itself.
- **Wave composition** is the only driver of spawn count: `Waves.spawnOrder`
  (`Waves.scala:28-29`) expands `composition` name→count. `WaveSpec.count` is
  only used for the loader's `count == total` validation (`ConfigLoader.scala:203-205`),
  never in gameplay — so scaling composition is safe.
- **Death handling is duplicated** in two places — `Sim.tickEffects`
  (`Sim.scala:183-185`, burn deaths) and `Sim.moveProjectiles`
  (`Sim.scala:292-299`, damage deaths) — each doing `gold += dead.size * KillReward`,
  `kills += dead.size`.
- `Config.biomes`/`Config.biome(id)` accessors exist (`Config.scala:60-62`);
  `RunContext.biome` is always a real id from `RunGen` (tutorial or one of the
  3 real biomes).

Decisions (confirmed)
---------------------
1. **Store effective resist on the enemy.** Add a `resist: Resistances` field to
   `Enemy` (default `Resistances()`), computed once in `Sim.spawn` as
   `spec.resist.layered(w.run.resistTheme)`, and delete the `def resist` accessor.
   This makes the exact value the player faced a stable snapshot (journal task07
   reads it straight off the enemy) and keeps `Sim.step` pure. Combine rule =
   **additive, clamped to [0,1]** (1 = immune), primary/secondary amounts added
   only to their matching kind; every other kind untouched (do NOT replace
   per-type resist). The "high/immune, medium" wording is descriptive — the
   magnitudes are already config (`0.4` / `0.2`-`0.3` etc.).
2. **Resist theme also scales burn/slow durations** for free: `moveProjectiles`
   already reads `e.resist(Fire)`/`e.resist(Slow)`, so once `resist` is the
   layered value, a Fire-resist biome shortens burns and a Slow-resist biome
   shortens slows — which is exactly what "resist that kind" means. No extra
   code.
3. **Environment rules dispatch by `Effect.kind` string** inside the existing
   `Sim.step` stages, with each kind's numeric params read from `Effect.params`
   (config). The kind strings are the code↔config contract (like enum names);
   the numbers are all config values. Dispatch map:
   - `openFloorPlan` → `applyAuras` (neighbor-based armor).
   - `crushed` → `tickEffects` + `moveProjectiles` (spawn hazards on heavy
     death) and `moveEnemies` (hazard slowdown); hazard timers tick in
     `tickEffects`.
   - `recycling` → `startWave` (swarm size), `moveEnemies` (leak cost),
     `tickEffects` + `moveProjectiles` (kill gold).
4. **"Heavy" is an explicit config flag, not a derived heuristic.** Add
   `heavy: Boolean = false` to `EnemySpec` (loader default false); mark `Brute`
   and `Shieldbearer` `heavy: true` in `config.json`. Cleanest data-driven way to
   say "dying heavies" (size/hp thresholds would silently misclassify e.g.
   Commander). Schema change is backward-compatible (default field).
5. **Hazard = world state with a countdown**, matching the existing
   `slowUntil`/`burnUntil` pattern (no global clock). `Hazard(x, y, radius,
   slowFactor, remaining)` on `World`; `remaining` decrements in `tickEffects`
   each step, pruned at <= 0. `radius` is authored in cells and multiplied by
   `Config.CellPx` in Sim. Hazards clear on level transition (`completeLevel`).
6. **Fractional leak cost via a `leakDamage` accumulator.** `livesLossMult =
   0.5` means a leak "costs" 0.5 lives; keep a `Float` pending counter on
   `World`, subtract whole lives when it crosses 1.0. Default (no recycling) is
   `mult = 1f`, which reproduces today's `lives -= leaked.size` exactly.
7. **`killGoldBonus` is a flat per-kill bonus** (additive), not a multiplier —
   consistent with the config naming convention (`*Mult` = multiplier, `*Bonus`
   = flat add). `swarmSizeMult` scales each composition count with
   `math.round(n * mult)` at `startWave`.
8. **Recycling/armor/hazard params read defensively** via `params.getOrElse(key,
   0f)` so a missing key no-ops rather than throwing; the bundled config always
   carries them.

Steps
-----

### Step 1 — `Model.scala`: enemy resist field + `Hazard` + `World` fields

Replace the `def resist` accessor with a field (defaulted so any stray
constructor/copy still compiles), and add a `layered` helper to `Resistances`:

```scala
case class Resistances(
    kinetic: Float = 0f,
    fire: Float = 0f,
    slow: Float = 0f,
    splash: Float = 0f,
    explosion: Float = 0f
):
  def apply(kind: DamageKind): Float = kind match
    case DamageKind.Kinetic   => kinetic
    case DamageKind.Fire      => fire
    case DamageKind.Slow      => slow
    case DamageKind.Splash    => splash
    case DamageKind.Explosion => explosion

  /** Base resist plus the run's resist theme, clamped to [0, 1] (1 = immune).
    * Only the primary/secondary kinds gain; all others pass through untouched.
    */
  def layered(theme: ResistTheme): Resistances =
    def addFor(kind: DamageKind): Float =
      if theme.primary == kind then theme.primaryAmount
      else if theme.secondary == kind then theme.secondaryAmount
      else 0f
    def c(base: Float, kind: DamageKind): Float =
      math.min(1f, math.max(0f, base + addFor(kind)))
    Resistances(
      c(kinetic, DamageKind.Kinetic),
      c(fire, DamageKind.Fire),
      c(slow, DamageKind.Slow),
      c(splash, DamageKind.Splash),
      c(explosion, DamageKind.Explosion)
    )
```

`Enemy` gains the field; delete the `def resist` line:

```scala
case class Enemy(
    id: Long,
    progress: Float,
    hp: Float,
    maxHp: Float,
    speed: Float,
    tpe: String,
    level: Int,
    resist: Resistances = Resistances(),
    slowUntil: Float = 0f,
    slowFactor: Float = 1f,
    burnDps: Float = 0f,
    burnUntil: Float = 0f,
    speedMult: Float = 1f,
    armor: Float = 0f
):
  def spec: EnemySpec      = Config.enemySpec(tpe)
  def pos: (Float, Float)  = Grid.positionAt(progress, level)
  def slowed: Boolean      = slowUntil > 0f
  def burning: Boolean     = burnUntil > 0f
  def buffed: Boolean      = speedMult > 1f || armor > 0f
  def effectiveSpeed: Float = (if slowed then speed * slowFactor else speed) * speedMult
```

Add `Hazard` after `Projectile` (or near `Enemy`):

```scala
/** A temporary ground hazard (a dead heavy's wreckage) that slows enemies moving
  * through it. [[remaining]] counts down each step.
  */
case class Hazard(x: Float, y: Float, radius: Float, slowFactor: Float, remaining: Float)
```

`World` gains two defaulted fields after `leaks`:

```scala
case class World(
    ...
    won: Boolean = false,
    kills: Int = 0,
    leaks: Int = 0,
    hazards: Vector[Hazard] = Vector.empty,
    leakDamage: Float = 0f
):
```

`Resistances.layered` references `ResistTheme` (in `GameConfig.scala`) — same
package, Scala 3 resolves top-level types across files, no ordering issue.

### Step 2 — `GameConfig.scala`: `heavy` flag on `EnemySpec`

```scala
case class EnemySpec(
    name: String,
    baseHp: Float,
    baseSpeed: Float,
    color: Color,
    size: Float,
    resist: Resistances,
    aura: Option[Aura],
    heavy: Boolean = false
)
```

### Step 3 — `ConfigLoader.scala`: parse `heavy`

In `parseEnemy`, add `heavy = v.getBoolean("heavy", false)` to the `EnemySpec(...)`
construction (libGDX `JsonValue.getBoolean(name, default)` does not throw on a
missing key).

### Step 4 — `config.json`: mark heavies + add `crushed.radius`

- `Brute` and `Shieldbearer`: add `"heavy": true` (others rely on the default
  `false`, matching the loader).
- `crushed` environment params: add a radius (cells):
  `{ "hazardDuration": 3.0, "slowFactor": 0.4, "radius": 1.0 }`.
- `openFloorPlan`/`recycling` params are already complete.

### Step 5 — `Sim.scala`: resist layering + env-rule hooks

**(a) spawn** — set the effective resist:

```scala
val e = Enemy(
  id = w.nextId,
  progress = 0f,
  hp = hp,
  maxHp = hp,
  speed = spec.baseSpeed,
  tpe = spec.name,
  level = w.level,
  resist = spec.resist.layered(w.run.resistTheme)
)
```

**(b) environment helpers** (private, near the top of `Sim`):

```scala
private def biomeEnv(w: World): Option[Effect] =
  Config.biomes.find(_.id == w.run.biome).flatMap(_.environment)

private def envParam(w: World, kind: String, key: String): Float =
  biomeEnv(w) match
    case Some(e) if e.kind == kind => e.params.getOrElse(key, 0f)
    case _                         => 0f

private def killReward(w: World): Float =
  Config.KillReward + envParam(w, "recycling", "killGoldBonus")
```

**(c) `applyAuras`** — fold openFloorPlan armor in; restructure so the early
return only fires when neither aura carriers nor the env rule is present:

```scala
private def applyAuras(w: World): World =
  val floorPlan = biomeEnv(w) match
    case Some(e) if e.kind == "openFloorPlan" =>
      Some((e.params.getOrElse("radius", 0f) * Config.CellPx, e.params.getOrElse("armorPerNeighbor", 0f)))
    case _ => None
  val carriers = w.enemies.filter(_.spec.aura.isDefined)
  if carriers.isEmpty && floorPlan.isEmpty then w
  else
    w.copy(enemies = w.enemies.map { e =>
      var speedMult = 1f
      var auraArmor = 0f
      carriers.foreach { src =>            // unchanged inner aura logic, sets auraArmor
        if src.id != e.id then src.spec.aura.foreach { a =>
          val (sx, sy) = src.pos
          val (ex, ey) = e.pos
          val dx = sx - ex; val dy = sy - ey
          if dx * dx + dy * dy <= a.radius * a.radius then
            a.kind match
              case AuraKind.SpeedBoost => speedMult = math.max(speedMult, 1f + a.magnitude)
              case AuraKind.Armor      => auraArmor = math.max(auraArmor, a.magnitude)
        }
      }
      var armor = auraArmor
      floorPlan.foreach { case (radius, per) =>
        val (ex, ey) = e.pos
        val neighbors = w.enemies.count { o =>
          if o.id == e.id then false
          else
            val (ox, oy) = o.pos
            val dx = ox - ex; val dy = oy - ey
            dx * dx + dy * dy <= radius * radius
        }
        armor += per * neighbors
      }
      e.copy(speedMult = speedMult, armor = armor)
    })
```

**(d) `tickEffects`** — tick hazards first (even with no enemies), then burn/slow
as today, but use `killReward` and spawn crushed hazards for heavy deaths:

```scala
private def tickEffects(w: World, dt: Float): World =
  val hazards = tickHazards(w.hazards, dt)
  val base = if hazards eq w.hazards then w else w.copy(hazards = hazards)
  if base.enemies.isEmpty then base
  else
    val updated = base.enemies.map { e =>               // unchanged burn/slow logic
      val burned =
        if e.burning then e.copy(hp = e.hp - e.burnDps * dt, burnUntil = e.burnUntil - dt)
        else e
      if burned.slowed then burned.copy(slowUntil = burned.slowUntil - dt) else burned
    }
    val (dead, alive) = updated.partition(_.hp <= 0f)
    if dead.isEmpty then base.copy(enemies = alive)
    else base.copy(
      enemies = alive,
      gold = base.gold + dead.size * killReward(base),
      kills = base.kills + dead.size,
      hazards = base.hazards ++ crushedHazards(base, dead)
    )

private def tickHazards(hazards: Vector[Hazard], dt: Float): Vector[Hazard] =
  if hazards.isEmpty then hazards
  else hazards.map(h => h.copy(remaining = h.remaining - dt)).filter(_.remaining > 0f)

private def crushedHazards(w: World, dead: Vector[Enemy]): Vector[Hazard] =
  biomeEnv(w) match
    case Some(e) if e.kind == "crushed" =>
      val radius  = e.params.getOrElse("radius", 0f) * Config.CellPx
      val slowF   = e.params.getOrElse("slowFactor", 1f)
      val duration = e.params.getOrElse("hazardDuration", 0f)
      dead.filter(_.spec.heavy).map { d =>
        val (x, y) = d.pos
        Hazard(x, y, radius, slowF, duration)
      }
    case _ => Vector.empty
```

**(e) `moveEnemies`** — apply hazard slowdown + recycling leak cost:

```scala
private def moveEnemies(w: World, dt: Float): World =
  val advanced = w.enemies.map(e => e.copy(progress = e.progress + e.effectiveSpeed * hazardMult(e, w.hazards) * dt))
  val (leaked, still) = advanced.partition(e => e.progress >= Grid.length(e.level))
  if leaked.isEmpty then w.copy(enemies = still)
  else
    val mult    = math.max(0f, envParam(w, "recycling", "livesLossMult")) match
      case m if m <= 0f => 1f
      case m            => m
    val pending = w.leakDamage + leaked.size * mult
    val whole   = pending.toInt
    w.copy(
      enemies = still,
      lives = math.max(0, w.lives - whole),
      leaks = w.leaks + leaked.size,
      leakDamage = pending - whole
    )

private def hazardMult(e: Enemy, hazards: Vector[Hazard]): Float =
  if hazards.isEmpty then 1f
  else
    val (ex, ey) = e.pos
    val factors = hazards.collect { case h if h.remaining > 0f =>
      val dx = h.x - ex; val dy = h.y - ey
      if dx * dx + dy * dy <= h.radius * h.radius then h.slowFactor else 1f
    }
    if factors.isEmpty then 1f else factors.min
```

**(f) `moveProjectiles`** — use `killReward` and spawn crushed hazards on death:

```scala
val (dead, alive) = damaged.partition(_.hp <= 0f)
w.copy(
  shots = fs.result(),
  enemies = alive,
  gold = w.gold + dead.size * killReward(w),
  kills = w.kills + dead.size,
  hazards = w.hazards ++ crushedHazards(w, dead)
)
```

**(g) `startWave`** — scale swarms for recycling:

```scala
private def startWave(w: World): World =
  val next = w.wave + 1
  val spec = Waves.spec(w.level, next)
  w.copy(
    breakTimer = 0f,
    wave = next,
    spawnQueue = scaledSpawnOrder(w, spec),
    spawnTimer = 0f
  )

private def scaledSpawnOrder(w: World, spec: WaveSpec): Vector[String] =
  val mult = envParam(w, "recycling", "swarmSizeMult")
  if mult <= 0f || mult == 1f then Waves.spawnOrder(spec)
  else spec.composition.flatMap { case (name, n) =>
    Vector.fill(math.max(0, math.round(n * mult)))(name)
  }
```

**(h) `completeLevel`** — clear hazards on the level transition (not `leakDamage`,
which is lives-related and persists with lives):

```scala
w.copy(
  level = next,
  wave = 0,
  towers = Vector.empty,
  enemies = Vector.empty,
  shots = Vector.empty,
  hazards = Vector.empty,
  spawnQueue = Vector.empty,
  ...
)
```

### Step 6 — Tests: `core/src/test/scala/td/EnvRulesTest.scala` (new)

Shared helper + focused, deterministic tests (no full-run timing):

```scala
package td

class EnvRulesTest extends munit.FunSuite:

  private def runFor(biomeId: String): RunContext =
    val b = Config.biome(biomeId)
    RunContext(seed = 7L, biome = b.id, resistTheme = b.resistTheme, roster = b.roster)

  private def enemy(run: RunContext, tpe: String, id: Long, progress: Float = 0f, hp: Float = 100f): Enemy =
    val spec = Config.enemySpec(tpe)
    Enemy(id = id, progress = progress, hp = hp, maxHp = 100f, speed = spec.baseSpeed,
      tpe = tpe, level = 0, resist = spec.resist.layered(run.resistTheme))
```

Tests:

```scala
  test("resist theme layers additively and leaves other kinds untouched") {
    val theme = ResistTheme(DamageKind.Kinetic, 0.5f, DamageKind.Fire, 0.25f)
    val base  = Resistances(kinetic = 0.35f, fire = 0.6f, slow = 0.7f)
    val got   = base.layered(theme)
    assertEquals(got.kinetic, 0.85f)
    assertEquals(got.fire, 0.85f)
    assertEquals(got.slow, 0.7f)
    assertEquals(got.splash, 0f)
    assertEquals(got.explosion, 0f)
  }

  test("resist theme clamps combined resist at 1.0 (immune)") {
    val got = Resistances(kinetic = 0.5f).layered(ResistTheme(DamageKind.Kinetic, 0.9f, DamageKind.Fire, 0.1f))
    assertEquals(got.kinetic, 1f)
  }

  test("spawned enemies carry the run's layered resist") {
    val run = RunGen.roll(123L)
    var w = Sim.initial(run).copy(breakTimer = 0f)
    var guard = 0
    while w.enemies.isEmpty && guard < 100000 do
      w = Sim.step(w, Config.MaxStepDt, Vector.empty)
      guard += 1
    assert(w.enemies.nonEmpty)
    val e = w.enemies.head
    val base = e.spec.resist
    assertEquals(e.resist.apply(run.resistTheme.primary),
      math.min(1f, base.apply(run.resistTheme.primary) + run.resistTheme.primaryAmount))
    assertEquals(e.resist.apply(run.resistTheme.secondary),
      math.min(1f, base.apply(run.resistTheme.secondary) + run.resistTheme.secondaryAmount))
  }

  test("openFloorPlan grants armor while enemies cluster") {
    val run = runFor("cube-farm")
    val e1  = enemy(run, "Grunt", 1L)
    val e2  = enemy(run, "Grunt", 2L)
    val w   = Sim.initial(run).copy(enemies = Vector(e1, e2), spawnQueue = Vector.empty, breakTimer = 999f)
    val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
    assert(stepped.enemies.forall(_.armor > 0f))
    val per = Config.biome("cube-farm").environment.get.params("armorPerNeighbor")
    assertEquals(stepped.enemies.head.armor, per)   // one neighbor each
  }

  test("crushed: a dying heavy leaves a temporary hazard") {
    val run   = runFor("shipping-yard")
    assert(Config.enemySpec("Brute").heavy)
    val dying = enemy(run, "Brute", 1L).copy(hp = 1f, burnDps = 100f, burnUntil = 10f)
    val w     = Sim.initial(run).copy(enemies = Vector(dying), spawnQueue = Vector.empty, breakTimer = 999f)
    val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
    assert(stepped.enemies.isEmpty)
    assert(stepped.hazards.nonEmpty)
    assert(stepped.hazards.head.remaining > 0f)
  }

  test("crushed: a dying light enemy leaves no hazard") {
    val run   = runFor("shipping-yard")
    assert(!Config.enemySpec("Ember").heavy)
    val dying = enemy(run, "Ember", 1L).copy(hp = 1f, burnDps = 100f, burnUntil = 10f)
    val w     = Sim.initial(run).copy(enemies = Vector(dying), spawnQueue = Vector.empty, breakTimer = 999f)
    assert(Sim.step(w, Config.MaxStepDt, Vector.empty).hazards.isEmpty)
  }

  test("recycling: leaks cost fewer lives") {
    val run = runFor("cardboard-wastes")
    val len = Grid.length(0)
    val e   = enemy(run, "Swift", 1L, progress = len - 1f).copy(speed = 100f)
    val w   = Sim.initial(run).copy(enemies = Vector(e), spawnQueue = Vector.empty, breakTimer = 999f)
    val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
    assertEquals(stepped.leaks, 1)
    assertEquals(stepped.lives, w.lives)      // 1 leak * 0.5 = 0.5 -> no whole life yet
    assertEquals(stepped.leakDamage, 0.5f)
  }

  test("recycling: swarms are larger") {
    def waveQueueSize(biomeId: String): Int =
      val w = Sim.initial(runFor(biomeId)).copy(breakTimer = Config.MaxStepDt)
      Sim.step(w, Config.MaxStepDt, Vector.empty).spawnQueue.size
    assertEquals(waveQueueSize("tutorial"), 8)         // Grunt 6 + Swift 2
    assertEquals(waveQueueSize("cardboard-wastes"), 11) // round(6*1.4)=8 + round(2*1.4)=3
  }
```

Files
-----
- `core/src/main/scala/td/Model.scala` — `Resistances.layered`, `Enemy.resist`
  field (replaces the `def`), new `Hazard`, `World` gains `hazards`/`leakDamage`.
- `core/src/main/scala/td/GameConfig.scala` — `EnemySpec.heavy: Boolean = false`.
- `core/src/main/scala/td/ConfigLoader.scala` — parse `heavy` (default false).
- `core/src/main/scala/td/Sim.scala` — resist layering in `spawn`; env-rule hooks
  in `applyAuras`/`tickEffects`/`moveEnemies`/`startWave`/`moveProjectiles`/
  `completeLevel`; helpers `biomeEnv`/`envParam`/`killReward`/`tickHazards`/
  `crushedHazards`/`hazardMult`/`scaledSpawnOrder`.
- `core/src/main/resources/config.json` — `heavy: true` on Brute/Shieldbearer;
  `crushed` gains `radius`.
- `core/src/test/scala/td/EnvRulesTest.scala` — new munit tests.

No changes to `RunGen.scala`, `Config.scala`, `Waves.scala`, `Grid.scala`,
`HeadlessMain.scala`, `Bot.scala`, or `Render.scala`.

Verify
------
1. `sbt compile`.
2. `sbt test` — existing 30 tests still pass (SimSmokeTest asserts only
   `run.seed`/`leaks`/`kills`, which are unaffected; RunGenTest/ConfigTest
   untouched) plus `EnvRulesTest`.
3. Resist determinism: same seed → identical effective resist. Covered by
   `spawned enemies carry the run's layered resist` (deterministic) and the
   direct `layered` unit tests.
4. Each environment rule demonstrably changes outcomes: the three rule tests
   (armor / hazard / leak-cost + swarm-size) assert the mechanics directly.
5. Headless spot-check across biomes: `sbt "headless/run --seed N"` for several
   `N`; observe `biome=` differs and `lives/kills/leaks` differ between biomes
   (find per-biome seeds with a quick loop, or rely on `RunGen.roll(N).biome`
   from a scratch script). The munit tests are the authoritative per-rule check.
6. Optional manual: `sbt "game/run"` still launches (needs a display); enemies
   in real biomes now show layered resists (renderer unaffected, no visual change
   required this task).

Notes / Open questions
----------------------
1. **Additive resist is the chosen combine rule** (task03 explicitly deferred
   "exact combination rule" here). Clamped at 1.0 so a theme + base resist can
   reach immunity but never "negative damage". If a future design wants
   multiplicative stacking, change only `Resistances.layered`.
2. **Tutorial is untouched**: its resist theme is 0.0/0.0 and `environment` is
   null, so `layered` is a no-op and no env rule fires.
3. `leakDamage` (fractional lives) persists across levels like `lives`; `hazards`
   clear on `completeLevel` (they are board state).
4. Env `radius`/`armorPerNeighbor`/`hazardDuration`/`slowFactor`/`livesLossMult`/
   `killGoldBonus`/`swarmSizeMult` are all config; only the *kind strings* and
   the "heavy" flag are code/config contract identifiers.
5. `hazardMult`/armor both reduce via `(1f - armor)` and `speed * mult`; armor is
   not clamped above 1 but `moveProjectiles` already guards with
   `math.max(0f, ...)` so no healing can occur.
6. `killGoldBonus` is a flat per-kill add. If the designer later wants a
   multiplier, add a separate `killGoldMult` param (keep names honest).
7. `swarmSizeMult` rounding is `math.round`; switch to `ceil` if "larger" should
   bias upward.
8. Enemies already on the field keep their spawned `resist` even across a config
   reload (base resist re-tunes via `spec`, theme resist is a run property).
   Acceptable; note for the journal task.

Implementation gotchas (added during review)
--------------------------------------------
- **`Enemy.resist` was a `def`**; a same-named `val` field must replace it (no
  duplicate). `Sim.spawn` is the only constructor, so set it there; keep the
  field defaulted so `.copy(...)` calls (e.g. `enemy(...).copy(hp = ...)` in
  tests) and the renderer's reads stay valid.
- `Config.biomes.find(_.id == ...)` (not `Config.biome(id)`) so a mid-run config
  reload that drops a biome degrades to "no rule" instead of `sys.error`.
- `parseEnemy` must use `v.getBoolean("heavy", false)` (the two-arg overload does
  not throw on a missing key), not the `b(...)` helper which throws.
- `startWave` runs *last* in the step pipeline, so after
  `Sim.step(w, dt, ...)` the fresh `spawnQueue` (scaled) is already populated and
  the swarm-size test reads it directly — no enemy has been popped yet.
- `tickEffects` must tick `hazards` even when `enemies` is empty (hazards are
  world state), so restructure the early-return as shown rather than appending
  to the existing `if w.enemies.isEmpty then w` guard.
- The two death sites (burn in `tickEffects`, damage in `moveProjectiles`) must
  BOTH add `crushedHazards(...)` and use `killReward(...)`; missing one yields a
  rule that only fires half the time.
- `math.round(n * mult)` returns `Long`; wrap in `.toInt` (or `math.max(0, ...)`)
  before `Vector.fill`.
- Do not touch `Sim.step`'s stage ordering — the env rules slot into the existing
  stages (`spawn` → `applyAuras` → `tickEffects` → `moveEnemies` → `fireTowers`
  → `moveProjectiles` → `advanceWave`); moving them would change when hazards/
  armor take effect.
