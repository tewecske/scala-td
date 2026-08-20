package td

import com.badlogic.gdx.graphics.Color

/** A buildable tower's tuning. `range` and `splashRadius` are stored in world
  * units (the JSON author writes them in cells; the loader multiplies by the
  * cell size).
  */
case class TowerSpec(
    name: String,
    cost: Int,
    color: Color,
    range: Float,
    fireInterval: Float,
    damage: Float,
    projectileSpeed: Float,
    kind: DamageKind,
    splashRadius: Float
)

/** An enemy archetype's tuning. `baseHp`/`baseSpeed` are the wave-1 baselines
  * (hp is scaled by the owning wave's [[WaveSpec.hpScale]]); resistances and the
  * optional aura come straight from here.
  */
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

case class BoardConfig(gridSize: Int, cellPx: Float, panelPx: Float)

/** I18N tuning: the active language and whether debug code-display starts on. */
case class I18nConfig(lang: String, showCodes: Boolean)

case class EconomyConfig(startGold: Int, startLives: Int, killReward: Int, levelBonus: Int)

case class CombatConfig(
    maxStepDt: Float,
    fastForward: Int,
    hitRadius: Float,
    burnDps: Float,
    burnDuration: Float,
    slowFactor: Float,
    slowDuration: Float,
    breakTime: Float,
    studyHits: Int
)

/** One wave of a level: a fixed enemy mix and its pacing. [[composition]] lists
  * exact per-type counts (enemy NAME -> count); the sim expands it into a
  * concrete spawn order by repeating each type `count` times, in listed order.
  */
case class WaveSpec(
    count: Int,
    spawnInterval: Float,
    hpScale: Float,
    composition: Vector[(String, Int)]
)

/** One level: a named route plus its own explicit wave table. */
case class LevelConfig(name: String, waves: Vector[WaveSpec], route: Route)

/** The walkable route, authored as corner waypoints. Path cells and the
  * polyline enemies walk are derived from these by [[Grid]].
  */
case class Route(waypoints: Vector[GridPos])

/** Shared palette, as parsed hex colors. */
case class Colors(
    background: Color,
    path: Color,
    build: Color,
    gridLine: Color,
    enemy: Color,
    shot: Color,
    shotFire: Color,
    shotFrost: Color,
    shotSplash: Color,
    shotBoom: Color,
    burnTint: Color,
    slowTint: Color,
    buffTint: Color,
    panel: Color,
    hoverOk: Color,
    hoverBad: Color,
    range: Color,
    buttonFill: Color,
    hpBarBack: Color,
    hpBarFill: Color,
    text: Color,
    dimText: Color
)

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

/** The whole tuning set, immutable, one value per loaded config. */
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
