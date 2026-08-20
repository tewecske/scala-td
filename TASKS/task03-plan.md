TASK 03 — PLAN (Config Schema Expansion, Data-Driven)
=====================================================

Research
--------
Verified against the current codebase:

- `GameConfig.scala` holds `TowerSpec`, `EnemySpec`, `BoardConfig`, `I18nConfig`,
  `EconomyConfig`, `CombatConfig`, `WaveSpec`, `LevelConfig`, `Route`, `Colors`,
  and top-level `GameConfig` (fields: `board, economy, combat, i18n, towers,
  enemies, levels, colors`). Towers/enemies are keyed by a single `name`; no
  `id` field exists anywhere yet.
- `ConfigLoader.scala` (`parseRoot`) reads `board.cellPx`, then `towers`,
  `enemies`, `colors`, then `levels` (validated against known enemy names), then
  the scalar blocks. Helpers: `obj`, `arr`, `s`, `f`, `i`, `b`, `c`,
  `requireUnique`, `parseColor`. Enums are parsed inline via
  `DamageKind.valueOf`/`AuraKind.valueOf`.
- `Config.scala` exposes `game`, per-block `def`s, `towerSpec(name)`/`enemySpec(name)`
  (lookup with `sys.error`), and `levels`/`levelCount`/`levelAt`.
- `Model.scala` `RunContext`: `biome = "tutorial"` (an **id**, not display),
  `relics: Vector[String]`, `augments: Vector[String]` (separate id namespaces),
  `mods`, `loadout`. `DamageKind = Kinetic, Fire, Slow, Splash, Explosion`;
  `AuraKind = SpeedBoost, Armor`; `Resistances` is a 5-field `Float` case class.
- `config.json` tower names: `Basic, Fire, Frost, Cannon, Bombard` (5). Enemy
  names: `Grunt, Swift, Brute, Ember, Frostling, Commander, Shieldbearer` (7).
  `"aura": null` means "absent" (loader checks `auraVal == null || !isObject`).
- `ConfigLoader.parse(text): Either[String, GameConfig]` is headless-safe and
  returns `Left(msg)` on error — exactly what "bad id / duplicate / unknown enum
  fails with a clear error" needs.
- Tests live in `core/src/test/scala/td/` (`SimSmokeTest`, `RngTest`,
  `MessagesTest`).

Decisions (confirmed)
---------------------
1. Two new enums, capitalized like `DamageKind`/`AuraKind`: `Rarity { Common,
   Rare, Legendary }`, `TalentLayer { Filler, Core, Deep }`. JSON uses exact
   case names (`"Common"`, `"Filler"`).
2. New types carry a separate `id` (stable key + cross-ref target) AND `name`
   (display). Towers/enemies keep legacy `name`-as-key. Cross-refs resolve
   against: enemy `name`s, tower `name`s, talent/relic `id`s. Deliberate
   asymmetry, documented below.
3. Generic effect = `Effect(kind: String, params: Map[String, Float])`. `kind`
   is free-form (editor renders generically). Boolean-y behaviors are encoded
   as floats by convention (`armorPierce: 1.0`, `maxBurnStacks: 1.0`,
   `debuffImmunity: 1.0`).
4. Relics and augments are TWO separate collections (matches `RunContext`'s
   separate `relics`/`augments` vectors). No `global: Boolean` on `Relic`.
5. `environment` on a biome is `Option[Effect]`; JSON `null` = no rule (tutorial).
6. Biome rosters reference existing enemy `name`s (themed archetypes not yet in
   config; see Open questions).
7. `resistTheme` magnitudes are floats (additive resistance fraction); exact
   combination rule is a later (Sim) task. Values are placeholders.
8. `requireUnique` checks the `id` of each new collection.

Steps
-----

### Step 1 — `GameConfig.scala`: new enums + case classes

Insert between `Colors` and the `GameConfig` case class:

```scala
enum Rarity:
  case Common, Rare, Legendary

enum TalentLayer:
  case Filler, Core, Deep

/** A generic tunable effect: an opaque kind plus a float parameter map. The
  * editor renders kind+params without knowing any specific mechanic.
  */
case class Effect(kind: String, params: Map[String, Float])

/** A biome's resist theme: which two damage kinds its roster resists and how
  * much (additive resistance fraction; exact combination rule is a later task).
  */
case class ResistTheme(
    primary: DamageKind,
    primaryAmount: Float,
    secondary: DamageKind,
    secondaryAmount: Float
)

/** A biome: a themed package of roster, environment rule, and resist theme.
  * `roster` holds enemy NAME ids; `environment` is None for "no rule".
  */
case class Biome(
    id: String,
    name: String,
    roster: Vector[String],
    environment: Option[Effect],
    resistTheme: ResistTheme
)

/** A twist that reroutes one tower kind: a nerf plus a silver-lining buff,
  * both encoded in `effect.params`.
  */
case class Twist(
    id: String,
    name: String,
    targetTower: String,
    effect: Effect
)

/** A global relic. `requiredTalent` gates the rarity tier: None for common,
  * Some(deep-talent-id) for rare/legendary.
  */
case class Relic(
    id: String,
    name: String,
    rarity: Rarity,
    effect: Effect,
    requiredTalent: Option[String]
)

/** A tower-socket augment (one socket per tower). */
case class Augment(
    id: String,
    name: String,
    effect: Effect
)

/** One node in the shared meta talent tree. `prereqs` are talent ids (empty for
  * fillers). `cost` is meta-currency. `effect` is generic.
  */
case class Talent(
    id: String,
    name: String,
    layer: TalentLayer,
    cost: Int,
    prereqs: Vector[String],
    effect: Effect
)
```

Extend `GameConfig` with the five collections:

```scala
case class GameConfig(
    board: BoardConfig,
    economy: EconomyConfig,
    combat: CombatConfig,
    i18n: I18nConfig,
    towers: Vector[TowerSpec],
    enemies: Vector[EnemySpec],
    levels: Vector[LevelConfig],
    colors: Colors,
    biomes: Vector[Biome],
    twists: Vector[Twist],
    relics: Vector[Relic],
    augments: Vector[Augment],
    talents: Vector[Talent]
)
```

### Step 2 — `ConfigLoader.scala`: helpers + parsing + validation

New helpers (near `requireUnique`):

```scala
private def enumValue[T](raw: String, what: String, values: Array[T]): T =
  values.find(_.toString == raw).getOrElse(
    throw IllegalArgumentException(
      s"unknown $what '$raw' (expected one of: ${values.map(_.toString).mkString(", ")})"
    )
  )

private def parseFloatMap(v: JsonValue): Map[String, Float] =
  (0 until v.size).map { idx =>
    val e = v.get(idx)
    e.name -> e.asFloat()
  }.toMap
```

New parse methods (after `parseColors`):

```scala
private def parseEffect(v: JsonValue): Effect =
  Effect(kind = s(v, "kind"), params = parseFloatMap(obj(v, "params")))

private def parseResistTheme(v: JsonValue): ResistTheme =
  ResistTheme(
    primary = enumValue(s(v, "primary"), "resist theme primary", DamageKind.values),
    primaryAmount = f(v, "primaryAmount"),
    secondary = enumValue(s(v, "secondary"), "resist theme secondary", DamageKind.values),
    secondaryAmount = f(v, "secondaryAmount")
  )

private def parseBiome(v: JsonValue, enemyNames: Set[String]): Biome =
  val id     = s(v, "id")
  val roster = arr(v, "roster").map(_.asString())
  if roster.isEmpty then
    throw IllegalArgumentException(s"biome '$id' has an empty roster")
  roster.foreach { e =>
    if !enemyNames.contains(e) then
      throw IllegalArgumentException(s"biome '$id' roster references unknown enemy '$e'")
  }
  val envVal = v.get("environment")
  val environment =
    if envVal == null || !envVal.isObject then None
    else Some(parseEffect(envVal))
  val theme = parseResistTheme(obj(v, "resistTheme"))
  if theme.primary == theme.secondary then
    throw IllegalArgumentException(s"biome '$id' resist theme primary and secondary must differ")
  Biome(id, name = s(v, "name"), roster, environment, theme)

private def parseTwist(v: JsonValue, towerNames: Set[String]): Twist =
  val id     = s(v, "id")
  val target = s(v, "targetTower")
  if !towerNames.contains(target) then
    throw IllegalArgumentException(s"twist '$id' targetTower references unknown tower '$target'")
  Twist(id, name = s(v, "name"), targetTower = target, effect = parseEffect(obj(v, "effect")))

private def parseRelic(v: JsonValue, talentIds: Set[String]): Relic =
  val id     = s(v, "id")
  val rarity = enumValue(s(v, "rarity"), "rarity", Rarity.values)
  val reqVal = v.get("requiredTalent")
  val requiredTalent =
    if reqVal == null || reqVal.isNull then None
    else
      val t = reqVal.asString()
      if !talentIds.contains(t) then
        throw IllegalArgumentException(s"relic '$id' requiredTalent references unknown talent '$t'")
      Some(t)
  if rarity == Rarity.Common && requiredTalent.isDefined then
    throw IllegalArgumentException(s"relic '$id' is Common but has requiredTalent")
  if rarity != Rarity.Common && requiredTalent.isEmpty then
    throw IllegalArgumentException(s"relic '$id' is $rarity but has no requiredTalent")
  Relic(id, name = s(v, "name"), rarity, effect = parseEffect(obj(v, "effect")), requiredTalent)

private def parseAugment(v: JsonValue): Augment =
  Augment(id = s(v, "id"), name = s(v, "name"), effect = parseEffect(obj(v, "effect")))

private def parseTalent(v: JsonValue): Talent =
  Talent(
    id = s(v, "id"),
    name = s(v, "name"),
    layer = enumValue(s(v, "layer"), "talent layer", TalentLayer.values),
    cost = i(v, "cost"),
    prereqs = arr(v, "prereqs").map(_.asString()),
    effect = parseEffect(obj(v, "effect"))
  )
```

Wire into `parseRoot` — after the existing `levels` block, read in this order
(talents first so relic/talent prereq checks resolve):

```scala
    val talents = arr(root, "talents").map(parseTalent)
    requireUnique(talents.map(_.id), "talent")
    val talentIds = talents.map(_.id).toSet
    talents.foreach { t =>
      t.prereqs.foreach { p =>
        if !talentIds.contains(p) then
          throw IllegalArgumentException(s"talent '${t.id}' prereq references unknown talent '$p'")
      }
    }

    val biomes = arr(root, "biomes").map(parseBiome(_, enemyNames))
    requireUnique(biomes.map(_.id), "biome")
    if biomes.isEmpty then throw IllegalArgumentException("at least one biome is required")
    if !biomes.exists(_.id == "tutorial") then
      throw IllegalArgumentException("a biome with id 'tutorial' is required")

    val twists = arr(root, "twists").map(parseTwist(_, towerNames))
    requireUnique(twists.map(_.id), "twist")

    val relics = arr(root, "relics").map(parseRelic(_, talentIds))
    requireUnique(relics.map(_.id), "relic")

    val augments = arr(root, "augments").map(parseAugment)
    requireUnique(augments.map(_.id), "augment")
```

Then pass the five new collections into `GameConfig(...)`. Keep existing
`DamageKind.valueOf`/`AuraKind.valueOf` lines untouched (out of scope).

### Step 3 — `Config.scala`: accessors

Add after the `// -- specs --` block:

```scala
  // -- biomes -----------------------------------------------------------------
  def biomes: Vector[Biome] = current.biomes
  def biome(id: String): Biome =
    current.biomes.find(_.id == id).getOrElse(sys.error(s"unknown biome '$id'"))

  // -- twists -----------------------------------------------------------------
  def twists: Vector[Twist] = current.twists
  def twist(id: String): Twist =
    current.twists.find(_.id == id).getOrElse(sys.error(s"unknown twist '$id'"))

  // -- relics -----------------------------------------------------------------
  def relics: Vector[Relic] = current.relics
  def relic(id: String): Relic =
    current.relics.find(_.id == id).getOrElse(sys.error(s"unknown relic '$id'"))

  // -- augments ---------------------------------------------------------------
  def augments: Vector[Augment] = current.augments
  def augment(id: String): Augment =
    current.augments.find(_.id == id).getOrElse(sys.error(s"unknown augment '$id'"))

  // -- talents ----------------------------------------------------------------
  def talents: Vector[Talent] = current.talents
  def talent(id: String): Talent =
    current.talents.find(_.id == id).getOrElse(sys.error(s"unknown talent '$id'"))
```

### Step 4 — `config.json`: new sections

Append five sections after `"colors"` (2-space indent, one-item-per-line for long
entries, matching the enemy style):

```json
  "biomes": [
    {
      "id": "tutorial",
      "name": "The Sandbox",
      "roster": ["Grunt", "Swift", "Ember", "Frostling"],
      "environment": null,
      "resistTheme": { "primary": "Kinetic", "primaryAmount": 0.0, "secondary": "Fire", "secondaryAmount": 0.0 }
    },
    {
      "id": "cube-farm",
      "name": "The Cube Farm",
      "roster": ["Grunt", "Swift", "Frostling", "Commander"],
      "environment": { "kind": "openFloorPlan", "params": { "armorPerNeighbor": 0.15, "radius": 1.5 } },
      "resistTheme": { "primary": "Slow", "primaryAmount": 0.4, "secondary": "Kinetic", "secondaryAmount": 0.2 }
    },
    {
      "id": "shipping-yard",
      "name": "The Shipping Yard",
      "roster": ["Brute", "Shieldbearer", "Ember", "Commander"],
      "environment": { "kind": "crushed", "params": { "hazardDuration": 3.0, "slowFactor": 0.4 } },
      "resistTheme": { "primary": "Fire", "primaryAmount": 0.4, "secondary": "Kinetic", "secondaryAmount": 0.3 }
    },
    {
      "id": "cardboard-wastes",
      "name": "The Cardboard Wastes",
      "roster": ["Swift", "Grunt", "Ember", "Brute"],
      "environment": { "kind": "recycling", "params": { "livesLossMult": 0.5, "killGoldBonus": 1.0, "swarmSizeMult": 1.4 } },
      "resistTheme": { "primary": "Splash", "primaryAmount": 0.35, "secondary": "Explosion", "secondaryAmount": 0.25 }
    }
  ],
  "twists": [
    { "id": "brittle", "name": "Brittle", "targetTower": "Frost",
      "effect": { "kind": "brittle", "params": { "slowDurationMult": 0.5, "kineticDamageVsSlowed": 1.5 } } },
    { "id": "backdraft", "name": "Backdraft", "targetTower": "Fire",
      "effect": { "kind": "backdraft", "params": { "burnTickRateMult": 2.0, "maxBurnStacks": 1.0 } } },
    { "id": "shaped-charge", "name": "Shaped Charge", "targetTower": "Bombard",
      "effect": { "kind": "shapedCharge", "params": { "radiusMult": 0.6, "armorPierce": 1.0 } } },
    { "id": "ricochet", "name": "Ricochet", "targetTower": "Basic",
      "effect": { "kind": "ricochet", "params": { "bounceEvery": 3.0, "fireIntervalMult": 1.25 } } }
  ],
  "relics": [
    { "id": "square-deal", "name": "Square Deal", "rarity": "Common", "requiredTalent": null,
      "effect": { "kind": "squareDeal", "params": { "goldMult": 1.1 } } },
    { "id": "box-office", "name": "Box Office", "rarity": "Rare", "requiredTalent": "conflagration",
      "effect": { "kind": "boxOffice", "params": { "killRewardBonus": 2.0 } } },
    { "id": "perimeter-alarm", "name": "Perimeter Alarm", "rarity": "Rare", "requiredTalent": "permafrost",
      "effect": { "kind": "perimeterAlarm", "params": { "leakWarningTime": 2.0 } } },
    { "id": "open-the-box", "name": "Open the Box", "rarity": "Rare", "requiredTalent": "demolition",
      "effect": { "kind": "openTheBox", "params": { "wavesPerRoll": 1.0 } } },
    { "id": "pandoras-box", "name": "Pandora's Box", "rarity": "Legendary", "requiredTalent": "conflagration",
      "effect": { "kind": "pandorasBox", "params": {} } }
  ],
  "augments": [
    { "id": "cornerstone", "name": "Cornerstone", "effect": { "kind": "cornerstone", "params": { "debuffImmunity": 1.0 } } },
    { "id": "sharpened-edge", "name": "Sharpened Edge", "effect": { "kind": "sharpenedEdge", "params": { "damageMult": 1.2 } } },
    { "id": "ninety-degrees", "name": "90 Degrees", "effect": { "kind": "ninetyDegrees", "params": {} } },
    { "id": "set-square", "name": "Set Square", "effect": { "kind": "setSquare", "params": {} } },
    { "id": "box-springs", "name": "Box Springs", "effect": { "kind": "boxSprings", "params": {} } },
    { "id": "vertigo", "name": "Vertigo", "effect": { "kind": "vertigo", "params": { "slowAmount": 0.25, "slowDuration": 2.0 } } }
  ],
  "talents": [
    { "id": "filler-damage", "name": "Square Up", "layer": "Filler", "cost": 1, "prereqs": [],
      "effect": { "kind": "damageBonus", "params": { "mult": 0.1 } } },
    { "id": "filler-range", "name": "Long Edge", "layer": "Filler", "cost": 1, "prereqs": [],
      "effect": { "kind": "rangeBonus", "params": { "mult": 0.1 } } },
    { "id": "filler-gold", "name": "Coin Slot", "layer": "Filler", "cost": 1, "prereqs": [],
      "effect": { "kind": "goldBonus", "params": { "mult": 0.15 } } },
    { "id": "pyromancer", "name": "Pyromancer", "layer": "Core", "cost": 3, "prereqs": ["filler-damage"],
      "effect": { "kind": "pyromancer", "params": {} } },
    { "id": "frostwarden", "name": "Frostwarden", "layer": "Core", "cost": 3, "prereqs": ["filler-range"],
      "effect": { "kind": "frostwarden", "params": {} } },
    { "id": "breaker", "name": "Breaker", "layer": "Core", "cost": 3, "prereqs": ["filler-damage"],
      "effect": { "kind": "breaker", "params": {} } },
    { "id": "permafrost", "name": "Permafrost", "layer": "Deep", "cost": 5, "prereqs": ["frostwarden"],
      "effect": { "kind": "permafrost", "params": {} } },
    { "id": "conflagration", "name": "Conflagration", "layer": "Deep", "cost": 5, "prereqs": ["pyromancer"],
      "effect": { "kind": "conflagration", "params": {} } },
    { "id": "demolition", "name": "Demolition", "layer": "Deep", "cost": 5, "prereqs": ["breaker"],
      "effect": { "kind": "demolition", "params": {} } }
  ]
```

Content mapping notes (which numbers are placeholders):

- **Biome rosters** are stand-ins mapped to the current 7 enemies because the
  themed archetypes (Cubicle, Brick, Milk Crate, etc.) do not exist yet — see
  Open questions. Resist themes cover all 5 damage kinds across the set.
  Magnitudes are tunable placeholders.
- **Environment params** — all placeholders.
- **Twists** match PLAN section 10 exactly (names, target tower, nerf/buff
  pairs); numeric params are placeholders but the nerf/buff structure is real.
- **Relics/augments** use exact PLAN names; `rarity`/`requiredTalent`/params are
  placeholders. Rare/Legendary relics point at deep talents to exercise gating.
- **Talents** = 3 cores (Pyromancer, Frostwarden, Breaker), 3 fillers
  (+damage/+range/+gold), 3 deep (Permafrost, Conflagration, Demolition). Costs,
  prereqs, and params are placeholders; layer names are the design's.

### Step 5 — Tests: `core/src/test/scala/td/ConfigTest.scala` (new)

Positive tests against `Config.game`, plus negative `ConfigLoader.parse` cases.
For the negative cases, build a single shared minimal-JSON string builder
(`minimalConfig(...)`) rather than duplicated literals — the sketches below show
intent only.

```scala
package td

class ConfigTest extends munit.FunSuite:
  private val cfg = Config.game

  test("new collections load from bundled config") {
    assert(cfg.biomes.nonEmpty)
    assert(cfg.twists.nonEmpty)
    assert(cfg.relics.nonEmpty)
    assert(cfg.augments.nonEmpty)
    assert(cfg.talents.nonEmpty)
  }

  test("tutorial biome exists and real biomes have distinct resist themes") {
    assert(cfg.biomes.exists(_.id == "tutorial"))
    assertEquals(cfg.biomes.size, 4)
    cfg.biomes.foreach(b => assertNotEquals(b.resistTheme.primary, b.resistTheme.secondary))
  }

  test("four twists each target an existing tower") {
    assertEquals(cfg.twists.size, 4)
    val towerNames = cfg.towers.map(_.name).toSet
    cfg.twists.foreach(t => assert(towerNames.contains(t.targetTower)))
  }

  test("biome rosters reference existing enemies") {
    val enemyNames = cfg.enemies.map(_.name).toSet
    cfg.biomes.foreach(b => b.roster.foreach(e => assert(enemyNames.contains(e))))
  }

  test("talent tree has 3 cores and prereqs resolve") {
    assertEquals(cfg.talents.count(_.layer == TalentLayer.Core), 3)
    cfg.talents.foreach(t => t.prereqs.foreach(p => assert(cfg.talents.exists(_.id == p))))
  }

  test("gated relics reference an existing talent") {
    cfg.relics.foreach { r =>
      r.requiredTalent.foreach(t => assert(cfg.talents.exists(_.id == t)))
      if r.rarity != Rarity.Common then assert(r.requiredTalent.isDefined)
    }
  }

  test("duplicate biome id is rejected") {
    val json = minimalConfig(biomes = "<two biomes with the same id>")
    assert(ConfigLoader.parse(json).left.exists(_.contains("duplicate biome")))
  }

  test("unknown rarity and unknown roster enemy are rejected") {
    assert(ConfigLoader.parse(minimalConfig(relicRarity = "Mythic")).left.exists(_.contains("unknown rarity")))
    assert(ConfigLoader.parse(minimalConfig(roster = "Brick")).left.exists(_.contains("unknown enemy")))
  }
```

Files
-----
- `core/src/main/scala/td/GameConfig.scala` — `Rarity`/`TalentLayer` enums +
  `Effect`, `ResistTheme`, `Biome`, `Twist`, `Relic`, `Augment`, `Talent`;
  `GameConfig` gains 5 fields.
- `core/src/main/scala/td/ConfigLoader.scala` — `enumValue` + `parseFloatMap`
  helpers; `parseEffect`, `parseResistTheme`, `parseBiome`, `parseTwist`,
  `parseRelic`, `parseAugment`, `parseTalent`; `parseRoot` wiring + cross-refs.
- `core/src/main/scala/td/Config.scala` — `biomes/biome`, `twists/twist`,
  `relics/relic`, `augments/augment`, `talents/talent`.
- `core/src/main/resources/config.json` — five new sections.
- `core/src/test/scala/td/ConfigTest.scala` — new tests (headless-safe).

No changes to `Model.scala` — `RunContext` already carries the id vectors;
`biome = "tutorial"` matches the required tutorial biome.

Verify
------
1. `sbt compile`.
2. `sbt test` — existing tests plus `ConfigTest` pass (exercises bundled-config
   parse with new sections + negative cases).
3. Spot-check bad inputs fail cleanly (`ConfigTest` cases): duplicate biome id,
   `rarity: "Mythic"`, roster referencing `"Brick"`, relic `requiredTalent`
   pointing at a missing id, talent prereq pointing at a missing id.
4. Optional manual: `sbt "game/run"` still launches (needs a display).

Notes / Open questions
----------------------
1. `id` vs `name` asymmetry: new types carry both; towers/enemies keep `name`-as-key.
   Cross-refs point at enemy `name`s, tower `name`s, and talent/relic `id`s.
2. "global vs augment" resolved: relics and augments are two collections (no
   `global` flag) since `RunContext` already splits them.
3. Effect params are floats only; booleans encoded as float conventions. If the
   editor later needs real bool/string/dropdown params, upgrade `params` to an
   ADT — keep `kind + params` shape so generic rendering survives.
4. `environment` is `Option[Effect]`; `null` = no rule; kinds are free-form strings.
5. Rarity gating is a hard loader check (rare/legendary require `requiredTalent`;
   common must not). Drop the check if a future design wants ungated rares.
6. Biome rosters are placeholders — PLAN section 5 references themed enemies not
   yet in config.json; revise `biomes[].roster` when the enemy task lands.
7. Resist-theme combination semantics unspecified (Sim's job, later).
8. Talent `cost`/`prereqs` graph is a placeholder; tree shape fixed.
9. No cycle-check on talent prereqs; cheap to add later.
10. `params: Map` ordering not guaranteed past 4 entries; switch to
    `Vector[(String, Float)]` if the editor needs stable field order.
11. `"params": {}` and `"prereqs": []` must be present (loader uses
    `obj(v, "params")`/`arr(v, "prereqs")`).

Implementation gotchas (added during review)
--------------------------------------------
- `requireUnique`'s message is `duplicate <what> name(s): ...`, so passing
  `what = "biome"` yields `duplicate biome name(s): x` — the negative test's
  `.contains("duplicate biome")` still matches. (Also fine for talent/relic/etc.)
- Parse order matters for the negative tests: `parseBiome` throws on an EMPTY
  roster BEFORE `requireUnique` runs (in `parseRoot` the array is mapped first).
  So the duplicate-id test fixture must give each biome a NON-empty roster that
  resolves against a real enemy (e.g. `"enemies": [{Grunt spec}]`, `roster:
  ["Grunt"]`), otherwise it trips "empty roster" instead of "duplicate".
- `parseFloatMap` matches the existing `parseWave` composition pattern
  (`child.name -> child.asFloat()`); empty `{}` -> empty map.
- `enumValue` uses `values.find(_.toString == raw)`; Scala 3 enums expose
  `.values`. Keep the existing `valueOf` calls for `DamageKind`/`AuraKind`
  untouched (out of scope).
