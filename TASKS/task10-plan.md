TASK 10 — PLAN (In-Run Draft: Relics + Augments + Socket + Reroll)
==================================================================

Research
--------
Verified against the current codebase (task09 + task05 + task03 are done;
baseline `sbt compile` + `sbt test` pass):

- **`RunContext`** (`Model.scala:114-125`) already carries `relics: Vector[String]`,
  `augments: Vector[String]`, and `talents: Vector[String]` (all defaulted empty).
  No `draftPool` field yet.
- **`Tower(cell, cooldown, tpe)`** (`Model.scala:83`) has no socket field.
- **`Command`** (`Model.scala:159-160`) has only `Build(cell, tpe)`.
- **`Sim.step`** (`Sim.scala:30-40`) is a pure pipeline; `applyCommand`
  (`Sim.scala:44-52`) folds `Command.Build`; `canBuild` (`Sim.scala:57-61`) is the
  single build-validity check shared with the renderer preview.
- **`completeLevel`** (`Sim.scala:190-212`) is the one "between levels" site: it
  clears towers/enemies/shots, awards the level bonus
  (`Config.LevelBonus * effects(w).goldMult`), increments `level`, and (on the
  last level) sets `won`. Towers are therefore **wiped at every level boundary** —
  an augment cannot be socketed into a tower from the previous level.
- **`Meta.effects(talents)`** (`Meta.scala:91-105`) already returns
  `TalentEffects` with `rerollUnlocked` (kind `"reroll"`), `owns(id)`,
  `activeCores`, `activeDeeps`. **No talent currently grants `reroll`** (task09
  note 3 defers it to task10), so `rerollUnlocked` is always false today.
- **Relic/augment data exists** (`config.json`): relics have `rarity` +
  `requiredTalent` + `effect`; augments have `effect`. Effect kinds in play:
  relics `squareDeal` (`goldMult`), `boxOffice` (`killRewardBonus`),
  `perimeterAlarm`, `openTheBox`, `pandorasBox`; augments `cornerstone`,
  `sharpenedEdge` (`damageMult`), `ninetyDegrees`, `setSquare`, `boxSprings`,
  `vertigo` (`slowAmount`, `slowDuration`). Loader already enforces: common
  relic has no `requiredTalent`; rare/legendary must have one
  (`ConfigLoader.scala:291-294`).
- **`Projectile`** (`Model.scala:89-98`) carries `kind/damage/speed/splashRadius/
  dest`; slow/burn are attached in `resolveImpact` (`Sim.scala:456-476`) by
  matching `p.kind` against `Config` constants — there is no per-shot slow field.
- **`ConfigLoader.parseRoot`** requires every scalar block via `obj(root, ...)`
  (`economy/combat/i18n/meta` are mandatory), so a new required `draft` block
  must be added to `ConfigTest.minimalConfig` too.
- **Existing tests that must keep passing**: `RunGenTest` asserts
  `RunGen.roll(s) == RunGen.roll(s)` and `tutorial(a).copy(seed=0) ==
  tutorial(b).copy(seed=0)`; `RngTest` asserts `Sim.initial(s)` determinism and
  `Sim.initial(42L).run == RunGen.roll(42L)`. None pin *values* of twists/
  biome, so adding a third RNG stream + `draftPool` is safe **if the biome and
  twist streams stay unchanged** (see Gotchas).

Decisions (confirmed)
---------------------
1. **The draft is a World sub-state, not a new `Phase`.** `World` gains
   `draft: Option[Draft]`, `draftCursor: Int`, `pendingAugment: Option[String]`.
   `Sim.step` short-circuits while `draft.isDefined` (like `isOver`/`won`):
   only draft commands are applied; no spawn/move/fire/advance. The draft
   screen is still rendered from `Phase.Playing(w)` (Main just checks
   `w.draft`).
2. **Augments are two-step** (`PickAugment` → `pendingAugment` → `AttachAugment`)
   because towers are wiped at each level boundary. Picking an augment offer
   banks it on `run.augments` and holds it as `pendingAugment`; the player
   sockets it into a built tower during the next level. `AttachAugment` enforces
   the single socket (refuses when `tower.augment` is already `Some`).
3. **Offers are pre-rolled once at run start** (roll-once rule) into
   `RunContext.draftPool: Vector[DraftOffer]`, filtered by owned deep talents.
   A reroll just reads the next slice; `Sim.step` never consumes RNG. The pool
   is `Config.DraftPoolRounds * Config.OfferCount` offers; reroll is refused when
   the pool is exhausted.
4. **Reroll is gold-based + talent-gated.** Reuses the existing
   `TalentEffects.rerollUnlocked` flag; a new `reroll` filler talent is added to
   `config.json`. Cost + growth come from a new `draft` config section (data
   before code).
5. **Effects are applied in Sim via kind-matching** (mirrors the existing
   biome-env and talent kind matching). Only the well-defined verbs get real
   behavior: `squareDeal` (gold mult), `boxOffice` (kill-reward bonus),
   `sharpenedEdge` (shot damage mult), `vertigo` (shot slow). The
   engine-only relics (`perimeterAlarm`, `openTheBox`, `pandorasBox`) and
   augments (`cornerstone`, `ninetyDegrees`, `setSquare`, `boxSprings`) are
   no-op placeholders, documented as such.
6. **`RunGen.roll`/`tutorial` gain a `talents` parameter** (default empty) and
   set both `run.talents` and `run.draftPool`. The tutorial rolls its draft pool
   from a **fixed seed** so `tutorial(a).copy(seed=0) == tutorial(b).copy(seed=0)`
   still holds (tutorial stays reproducible).

Steps
-----

### Step 1 — `Model.scala`: draft types, socket, run/state fields, commands

Add the offer + draft state types just before `RunContext` (they must precede
`RunContext` because `draftPool` references `DraftOffer`):

```scala
enum DraftOffer:
  case Relic(id: String)
  case Augment(id: String)

/** The between-level draft: the offers on the table and the current gold cost
  * to reroll them (grows with each reroll).
  */
case class Draft(offers: Vector[DraftOffer], rerollCost: Int)
```

`Tower` gains the single socket (defaulted so all existing call sites compile):

```scala
case class Tower(cell: GridPos, cooldown: Float, tpe: String, augment: Option[String] = None):
  def spec: TowerSpec = Config.towerSpec(tpe)
```

`RunContext` gains the pre-rolled pool (after `talents`):

```scala
    talents: Vector[String] = Vector.empty,
    draftPool: Vector[DraftOffer] = Vector.empty
```

`World` gains three defaulted fields (after `run`):

```scala
    run: RunContext,
    draft: Option[Draft] = None,
    draftCursor: Int = 0,
    pendingAugment: Option[String] = None,
    won: Boolean = false,
```

`Command` gains four cases (kept pure data):

```scala
enum Command:
  case Build(cell: GridPos, tpe: String)
  case PickRelic(relicId: String)
  case PickAugment(augmentId: String)
  case AttachAugment(cell: GridPos)
  case Reroll()
```

`Phase` is unchanged (the draft is `Playing(w)` with `w.draft.isDefined`).

### Step 2 — `core/src/main/scala/td/DraftGen.scala` (new)

Pre-rolls the pool once, filtered by talents. Pure: same seed + talents =>
identical pool.

```scala
package td

/** Pre-rolls the between-level draft pool once per run, filtered by the
  * player's owned talents. Pure: same seed + talents => identical pool.
  */
object DraftGen:
  /** Fixed seed for the tutorial's pool so the tutorial stays reproducible. */
  val TutorialPoolSeed: Long = 0x5EEDDRAFTL

  /** Relics the player can actually roll: common (no requirement) plus any
    * rare/legendary whose required deep talent they own.
    */
  def eligibleRelics(talents: Vector[String]): Vector[Relic] =
    val owned = Meta.effects(talents)
    Config.relics.filter(r => r.requiredTalent.forall(owned.owns))

  /** The full offer pool: eligible relics + every augment (augments are ungated). */
  def eligibleItems(talents: Vector[String]): Vector[DraftOffer] =
    eligibleRelics(talents).map(r => DraftOffer.Relic(r.id)) ++
      Config.augments.map(a => DraftOffer.Augment(a.id))

  /** Roll `DraftPoolRounds * OfferCount` offers. Offers within a round are
    * distinct; repeats across rounds are possible and are guarded at pick time.
    */
  def rollPool(rng: Rng, talents: Vector[String]): Vector[DraftOffer] =
    val items = eligibleItems(talents)
    if items.isEmpty then Vector.empty
    else
      val n = Config.OfferCount
      (0 until Config.DraftPoolRounds).foldLeft((Vector.empty[DraftOffer], rng)) {
        case ((acc, r), _) =>
          val (round, r2) = rollRound(items, r, n)
          (acc ++ round, r2)
      }._1

  private def rollRound(items: Vector[DraftOffer], rng: Rng, n: Int): (Vector[DraftOffer], Rng) =
    val idx = items.indices.toArray
    var r   = rng
    var i   = 0
    while i < n && i < idx.length do
      val (j, r2) = r.nextInt(idx.length - i)
      val k       = i + j
      val tmp     = idx(i); idx(i) = idx(k); idx(k) = tmp
      r = r2
      i += 1
    (idx.take(n).map(items(_)).toVector, r)
```

### Step 3 — `RunGen.scala`: roll the pool into the run

Keep the biome and twist streams byte-identical to task05 (derive the draft
stream from a further split of `twistRng`, so the biome/twist mapping does not
shift):

```scala
def roll(seed: Long, talents: Vector[String] = Vector.empty): RunContext =
  val (biomeRng, twistRng) = Rng(seed).split()
  val (draftRng, _)        = twistRng.split()
  val biome = pickBiome(biomeRng)
  RunContext(
    seed = seed,
    biome = biome.id,
    resistTheme = biome.resistTheme,
    twists = pickTwists(twistRng),
    roster = biome.roster,
    talents = talents,
    draftPool = DraftGen.rollPool(draftRng, talents)
  )

def tutorial(seed: Long = RunContext.DefaultSeed, talents: Vector[String] = Vector.empty): RunContext =
  val biome = Config.biome("tutorial")
  RunContext(
    seed = seed,
    biome = biome.id,
    resistTheme = biome.resistTheme,
    twists = Vector.empty,
    roster = biome.roster,
    talents = talents,
    draftPool = DraftGen.rollPool(Rng(DraftGen.TutorialPoolSeed), talents)
  )
```

### Step 4 — `GameConfig.scala` + `ConfigLoader.scala` + `Config.scala`: draft config

`GameConfig.scala` — new case class (near `MetaConfig`) + field:

```scala
/** Between-level draft tuning: how many offers, what a reroll costs, how the
  * cost grows per reroll, and how many rounds of offers are pre-rolled.
  */
case class DraftConfig(offerCount: Int, rerollCost: Int, rerollCostGrowth: Int, poolRounds: Int)
```

Append `draft: DraftConfig` to `GameConfig`.

`ConfigLoader.scala` — parse in `parseRoot` (mandatory, like `meta`):

```scala
    val draft = obj(root, "draft")
    ...
      draft = DraftConfig(i(draft, "offerCount"), i(draft, "rerollCost"),
        i(draft, "rerollCostGrowth"), i(draft, "poolRounds")),
```

`Config.scala` — accessors (place after the `meta` block):

```scala
  // -- draft -----------------------------------------------------------------
  def OfferCount: Int       = current.draft.offerCount
  def RerollCost: Int       = current.draft.rerollCost
  def RerollCostGrowth: Int = current.draft.rerollCostGrowth
  def DraftPoolRounds: Int  = current.draft.poolRounds
```

### Step 5 — `config.json`: draft section + reroll talent

Add after `"meta"`:

```json
  "draft": { "offerCount": 3, "rerollCost": 25, "rerollCostGrowth": 15, "poolRounds": 10 },
```

Add a reroll talent to the `talents` array (kind `"reroll"` is the contract key
`Meta.effects` already reads):

```json
    { "id": "reroll", "name": "Restock", "layer": "Filler", "cost": 2, "prereqs": [],
      "effect": { "kind": "reroll", "params": {} } }
```

### Step 6 — `Sim.scala`: draft state machine + effect application

Short-circuit `step` while drafting:

```scala
  def step(w0: World, dt: Float, cmds: Vector[Command]): World =
    if w0.isOver || w0.won then w0
    else if w0.draft.isDefined then cmds.foldLeft(w0)(applyCommand)
    else
      // ... existing pipeline unchanged ...
```

`applyCommand` — guard `Build` during a draft, add the four cases:

```scala
  private def applyCommand(w: World, c: Command): World = c match
    case Command.Build(cell, tpe) =>
      val spec = Config.towerSpec(tpe)
      if w.draft.isEmpty && canBuild(w, cell, tpe) then
        w.copy(towers = w.towers :+ Tower(cell, 0f, tpe), gold = w.gold - spec.cost)
      else w

    case Command.PickRelic(relicId) =>
      w.draft match
        case Some(d) if d.offers.contains(DraftOffer.Relic(relicId)) && !w.run.relics.contains(relicId) =>
          beginNextLevel(w.copy(run = w.run.copy(relics = w.run.relics :+ relicId)))
        case _ => w

    case Command.PickAugment(augmentId) =>
      w.draft match
        case Some(d) if d.offers.contains(DraftOffer.Augment(augmentId)) && !w.run.augments.contains(augmentId) =>
          beginNextLevel(w.copy(
            run = w.run.copy(augments = w.run.augments :+ augmentId),
            pendingAugment = Some(augmentId)
          ))
        case _ => w

    case Command.Reroll() =>
      w.draft match
        case Some(d) if canReroll(w, d) =>
          w.copy(
            draft = Some(Draft(nextOffers(w), d.rerollCost + Config.RerollCostGrowth)),
            draftCursor = w.draftCursor + Config.OfferCount,
            gold = w.gold - d.rerollCost
          )
        case _ => w

    case Command.AttachAugment(cell) =>
      if canAttachAugment(w, cell) then
        val aug = w.pendingAugment.get
        val idx = w.towers.indexWhere(_.cell == cell)
        w.copy(
          towers = w.towers.updated(idx, w.towers(idx).copy(augment = Some(aug))),
          pendingAugment = None
        )
      else w
```

New shared check (mirrors the `canBuild` preview/rule pattern so Render and Sim
never disagree):

```scala
  /** Whether clicking a cell would socket the held augment into the tower there.
    * Shared by the renderer preview and the rule.
    */
  def canAttachAugment(w: World, cell: GridPos): Boolean =
    w.pendingAugment.isDefined && w.towers.exists(t => t.cell == cell && t.augment.isEmpty)
```

Draft helpers + level transition:

```scala
  private def nextOffers(w: World): Vector[DraftOffer] =
    w.run.draftPool.slice(w.draftCursor, w.draftCursor + Config.OfferCount)

  private def canReroll(w: World, d: Draft): Boolean =
    effects(w).rerollUnlocked && w.gold >= d.rerollCost && nextOffers(w).nonEmpty
```

Replace the level-transition branch of `completeLevel` (the `won` path is
unchanged; the else-branch now opens a draft instead of moving straight on):

```scala
  private def completeLevel(w: World): World =
    if w.level + 1 >= Config.levelCount then
      w.copy(won = true, enemies = Vector.empty, shots = Vector.empty,
        spawnQueue = Vector.empty, breakTimer = 0f)
    else enterDraft(w)

  /** Between levels: clear the field and present the next pre-rolled offers.
    * The level number does NOT advance until the player picks.
    */
  private def enterDraft(w: World): World =
    w.copy(
      draft = Some(Draft(nextOffers(w), Config.RerollCost)),
      draftCursor = w.draftCursor + Config.OfferCount,
      towers = Vector.empty,
      enemies = Vector.empty,
      shots = Vector.empty,
      spawnQueue = Vector.empty,
      spawnTimer = 0f,
      breakTimer = Waves.breakTime,
      hazards = Vector.empty
    )

  /** A pick was made: advance to the next level. Towers/enemies/shots are
    * cleared, the level bonus is awarded, and the draft closes. `pendingAugment`
    * (if any) is carried over for socketing during play.
    */
  private def beginNextLevel(w: World): World =
    w.copy(
      level = w.level + 1,
      wave = 0,
      towers = Vector.empty,
      enemies = Vector.empty,
      shots = Vector.empty,
      spawnQueue = Vector.empty,
      spawnTimer = 0f,
      breakTimer = Waves.breakTime,
      gold = w.gold + math.round(Config.LevelBonus * effects(w).goldMult * relicGoldMult(w)),
      hazards = Vector.empty,
      draft = None
    )
```

Relic effect helpers + injection points:

```scala
  private def relicGoldMult(w: World): Float =
    w.run.relics.foldLeft(1f) { (acc, id) =>
      val r = Config.relic(id)
      if r.effect.kind == "squareDeal" then acc * r.effect.params.getOrElse("goldMult", 1f) else acc
    }

  private def relicKillBonus(w: World): Float =
    w.run.relics.foldLeft(0f) { (acc, id) =>
      val r = Config.relic(id)
      if r.effect.kind == "boxOffice" then acc + r.effect.params.getOrElse("killRewardBonus", 0f) else acc
    }
```

`killReward` (currently `Sim.scala:75-76`) becomes:

```scala
  private def killReward(w: World): Float =
    (Config.KillReward + envParam(w, "recycling", "killGoldBonus") + relicKillBonus(w)) *
      effects(w).goldMult * relicGoldMult(w)
```

Augment effect helper + `fireTowers`/`resolveImpact` wiring:

```scala
  private case class AugmentFx(damageMult: Float, slow: Option[(Float, Float)])

  private def augmentFx(t: Tower): AugmentFx =
    t.augment match
      case None => AugmentFx(1f, None)
      case Some(id) =>
        val a = Config.augment(id)
        a.effect.kind match
          case "sharpenedEdge" => AugmentFx(a.effect.params.getOrElse("damageMult", 1f), None)
          case "vertigo"       => AugmentFx(1f, Some((a.effect.params.getOrElse("slowAmount", 0f), a.effect.params.getOrElse("slowDuration", 0f))))
          case _               => AugmentFx(1f, None)
```

In `fireTowers`, compute `val fx = augmentFx(t)` per tower and fold into the shot:

```scala
            fresh += Projectile(
              t.cell.centerX, t.cell.centerY, e.id, spec.kind,
              spec.damage * eff.damageMult * fx.damageMult,
              spec.projectileSpeed, spec.splashRadius, dest, fx.slow
            )
```

`Projectile` (`Model.scala`) gains a slow field (defaulted, so existing
constructions compile):

```scala
case class Projectile(
    x: Float, y: Float, targetId: Long, kind: DamageKind, damage: Float,
    speed: Float, splashRadius: Float, dest: Option[(Float, Float)],
    slow: Option[(Float, Float)] = None
):
```

`resolveImpact` overlays the projectile slow on the kind-based `Hit`:

```scala
    targets.map { e =>
      val base = p.kind match
        case DamageKind.Kinetic   => Hit(e.id, DamageKind.Kinetic, p.damage, None, None)
        case DamageKind.Fire      => Hit(e.id, DamageKind.Fire, p.damage, Some((Config.BurnDps, Config.BurnDuration)), None)
        case DamageKind.Slow      => Hit(e.id, DamageKind.Slow, p.damage, None, Some((Config.SlowFactor, Config.SlowDuration)))
        case DamageKind.Splash    => Hit(e.id, DamageKind.Splash, p.damage, None, None)
        case DamageKind.Explosion => Hit(e.id, DamageKind.Explosion, p.damage, None, None)
      p.slow.fold(base)(s => base.copy(slow = Some(s)))
    }
```

(`vertigo` on a `Slow` tower overwrites the default slow with the augment's —
noted in Open questions.)

### Step 7 — `game/src/main/scala/td/Main.scala`: talents into RunGen + draft/attach input

`newRun`/`startTutorial` now pass active talents straight into the roll (which
sets `run.talents` and rolls a correctly-gated pool) — drop the `.copy(talents=...)`:

```scala
  private def newRun(): Phase =
    fast = false
    val w = Sim.initial(RunGen.roll(System.nanoTime(), Meta.active().talents))
    beginRun(w)
    Phase.Playing(w)

  private def startTutorial(): Phase =
    fast = false
    val w = Sim.initial(RunGen.tutorial(System.nanoTime(), Meta.active().talents))
    beginRun(w)
    Phase.Playing(w)
```

In `Phase.Playing(w)`, branch on `w.draft`:

- `w.draft.isDefined`: do **not** step the sim; call `gfx.drawDraft(w)`; map
  clicks on the three offer rows to `Command.PickRelic(id)` /
  `Command.PickAugment(id)` and the reroll button to `Command.Reroll()` (only
  enabled when `Meta.effects(w.run.talents).rerollUnlocked` and
  `w.gold >= w.draft.get.rerollCost`).
- `w.pendingAugment.isDefined` during play: when the player clicks a cell that
  `Sim.canAttachAugment(w, cell)` accepts, issue `Command.AttachAugment(cell)`
  instead of `Command.Build`; otherwise build as usual. Render draws a socket
  hint.

`toRecap`/`Meta.bankRun` are unchanged.

### Step 8 — `game/src/main/scala/td/Render.scala`: offer UI + attach preview

- `drawDraft(w)`: title + the three offers. For each offer print its kind label
  (`draft.relic` / `draft.augment`), display name (`Config.relic(id).name` /
  `Config.augment(id).name`), and for relics its rarity. Draw a reroll button
  (dimmed when locked or unaffordable) and the current gold.
- `drawGame`/`drawTowers`: when `w.pendingAugment.isDefined`, draw the held
  augment name in the HUD and highlight towers whose `augment` is `None` (the
  `canAttachAugment` preview, colored like the build hover).

All labels go through `Messages.t(...)`.

### Step 9 — `messages_en.properties`: draft strings

```properties
draft.title=CHOOSE A BOON
draft.relic=relic
draft.augment=augment
draft.pick=pick
draft.reroll=reroll (%d gold)
draft.rerollLocked=reroll locked (requires talent)
draft.socketHint=click a tower to socket the augment
draft.rarity.Common=common
draft.rarity.Rare=rare
draft.rarity.Legendary=legendary
```

(Only `messages_en.properties` exists; no other language files to update.)

### Step 10 — Tests

`core/src/test/scala/td/DraftTest.scala` (new) — headless munit:

1. `eligibleRelics gates by deep talent`: `DraftGen.eligibleRelics(Vector.empty)`
   contains only `Rarity.Common`; `DraftGen.eligibleRelics(Vector("conflagration"))`
   contains `pandoras-box` (Legendary) and `box-office` (Rare).
2. `draft pool is deterministic and shallow pools have no legendaries`:
   `DraftGen.rollPool(Rng(42L), Vector.empty)` equals itself; every relic offer
   is `Common`.
3. `completing a level opens a draft`: build a World at the last wave with no
   enemies (`wave = Waves.waveCount(0)`, empty `spawnQueue`/`enemies`), step
   once, assert `draft.isDefined` and `offers.size == Config.OfferCount`.
4. `reroll unavailable without the reroll talent`: same World stepped into a
   draft, then `Command.Reroll()` leaves the World unchanged.
5. `reroll spends gold and rerolls with the talent`: run with
   `talents = Vector("reroll")`, step into a draft, `Command.Reroll()`, assert
   `gold` drops by `Config.RerollCost`, `draftCursor` advances, and
   `rerollCost` grows by `Config.RerollCostGrowth`.
6. `picking a relic applies it globally and advances the level`: set
   `draft = Some(Draft(Vector(DraftOffer.Relic("square-deal"), ...), ...))`,
   `Command.PickRelic("square-deal")`, assert `run.relics == Vector("square-deal")`,
   `draft == None`, `level == 1`.
7. `picking an augment holds it pending`: `Command.PickAugment("vertigo")`
   asserts `run.augments`, `pendingAugment`, `draft == None`, `level == 1`.
8. `augment attaches to exactly one tower; second socket refused`: World with a
   tower + `pendingAugment = Some("sharpened-edge")`; `AttachAugment(cell)` sets
   `tower.augment` and clears `pendingAugment`; re-attaching a second augment to
   the same tower is refused.
9. `"reroll" talent sets rerollUnlocked`: `Meta.effects(Vector("reroll")).rerollUnlocked`.

`core/src/test/scala/td/ConfigTest.scala` — add the `draft` block to
`minimalConfig(...)` (required section) and a positive assertion:

```scala
  test("draft tuning loads from config") {
    assertEquals(cfg.draft.offerCount, 3)
    assertEquals(cfg.draft.rerollCost, 25)
    assertEquals(cfg.draft.poolRounds, 10)
  }
```

Files
-----
- `core/src/main/scala/td/Model.scala` — `DraftOffer`, `Draft`, `Tower.augment`,
  `RunContext.draftPool`, `World.draft/draftCursor/pendingAugment`, new
  `Command` cases, `Projectile.slow`.
- `core/src/main/scala/td/DraftGen.scala` — NEW (`eligibleRelics`,
  `eligibleItems`, `rollPool`).
- `core/src/main/scala/td/RunGen.scala` — `talents` param; roll `draftPool`.
- `core/src/main/scala/td/GameConfig.scala` — `DraftConfig` + `GameConfig.draft`.
- `core/src/main/scala/td/ConfigLoader.scala` — parse the `draft` section.
- `core/src/main/scala/td/Config.scala` — `OfferCount/RerollCost/RerollCostGrowth/
  DraftPoolRounds`.
- `core/src/main/resources/config.json` — `draft` section + `reroll` talent.
- `core/src/main/scala/td/Sim.scala` — draft state machine, `canAttachAugment`,
  relic/augment effect application.
- `game/src/main/scala/td/Main.scala` — pass talents; draft/attach input.
- `game/src/main/scala/td/Render.scala` — `drawDraft`, attach preview.
- `core/src/main/resources/messages/messages_en.properties` — draft strings.
- `core/src/test/scala/td/DraftTest.scala` — NEW.
- `core/src/test/scala/td/ConfigTest.scala` — `minimalConfig` + `draft` assertion.

Verify
------
1. `sbt compile`.
2. `sbt test` — existing suite plus `DraftTest` and the `ConfigTest` addition.
3. Determinism: `DraftGen.rollPool(Rng(s), t) == DraftGen.rollPool(Rng(s), t)`;
   `Sim.initial(seed)` still steps deterministically (existing `RngTest`).
4. `sbt "headless/run --seed 1"` twice → identical summary (draft pool is
   pre-rolled; step never consumes RNG).
5. Optional manual (`sbt "game/run"`, needs a display): clear a level, see the
   3-offer draft, pick/reroll/attach; without the reroll talent the reroll
   button is disabled.

Notes / Open questions
----------------------
1. **Augment lifetime**: augments socket into a tower, but towers are cleared at
   every level boundary, so a socketed augment effectively lasts one level
   (though the augment stays *owned* in `run.augments`). Making augments re-
   socketable each level (a held-augments loadout) is a later concern; task10
   establishes the socket + one-augment-per-tower rule.
2. **Relics that are only "engine"** (`perimeterAlarm` leak warning,
   `openTheBox` per-wave roll, `pandorasBox`) and **augments** `cornerstone`/
   `ninetyDegrees`/`setSquare`/`boxSprings` have no numeric effect defined here
   and are deliberately no-ops. Their params exist in `config.json` (task03) for
   a future engine task to consume. The `Effect.kind -> behavior` mapping is
   code, not data (the *values* are data), matching the existing biome-env and
   talent kind matching.
3. **`requiredTalent` gating** is exactly the loader's contract: common relics
   have no requirement, rare/legendary point at a deep talent id. `DraftGen`
   filters on `Meta.effects(talents).owns(id)`, so a shallow player can never
   roll a legendary (Verify requirement).
4. **Reroll growth**: cost rises by `Config.RerollCostGrowth` each reroll; the
   pool is bounded by `DraftPoolRounds`, after which rerolls are refused. Tune
   `poolRounds` if deeper runs need more.
5. `Config.DraftPoolRounds` × `Config.OfferCount` (30) comfortably covers the
   2 drafts of a 3-level run plus up to 8 rerolls.
6. `tutorial` now rolls a draft pool from a fixed seed (tutorial still teaches
   drafting per PLAN section 5), preserving "byte-identical across seeds".
7. The draft is a `World` sub-state rather than a `Phase`; if the menu/briefing
   task later wants a dedicated `Phase.Draft`, it can wrap `Playing(w)` with
   `w.draft.isDefined`.

Implementation gotchas
----------------------
- **`draftPool` must be rolled with the real talents.** `Main` currently does
  `RunGen.roll(seed).copy(talents = ...)`; if left like that the pool would be
  rolled *shallow* even for a deep player. Pass talents into `roll`/`tutorial`
  and remove the `.copy(talents = ...)`.
- **Keep biome/twist streams stable.** `RunGen.roll` must still derive the
  biome and twist from the *first* `split()` result exactly as task05 did, and
  derive the draft stream from a *further* split of the twist stream — not a
  three-way split — so `RunGenTest`/`RngTest` (which don't pin values but rely
  on determinism and the seed echo) and the tutorial byte-identity test keep
  passing.
- **`ConfigTest.minimalConfig` needs the `draft` block** (it's a required
  `obj(root, "draft")` section) or the negative-parse tests break.
- **`Draft`/`DraftOffer` must be declared before `RunContext`** in `Model.scala`
  (the `draftPool` field references `DraftOffer`); `Draft` before `World`.
- **`step` must short-circuit on `draft.isDefined`** *before* the normal
  pipeline, otherwise `advanceWave` would immediately re-complete the level.
- **`Projectile.slow` default `None`** keeps `MetaTest`'s `Projectile(...)`
  construction and `resolveImpact` compiling.
- **`Tower`/`Projectile`/`World`/`RunContext` new fields are all defaulted** so
  every existing `copy`/constructor site (Sim, Bot, tests) still compiles.
- **`canAttachAugment` is the single source of truth** for socket validity,
  used by both Render (preview) and `applyCommand` (rule) — same pattern as
  `canBuild`.
- `DraftOffer` pattern matches in Render/Main use `DraftOffer.Relic(id)` /
  `DraftOffer.Augment(id)`; the `id` field is the constructor parameter.
