package td

import com.badlogic.gdx.graphics.Color

/** A buildable tower's tuning. Distances and speeds are in cells (and cells per
  * second); the simulation scales them by the active level's cell size.
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

/** Which side of a fork a lane (or a side-taking enemy) favours. A branch
  * declares its side; the trunk lane it bypasses is the opposite side.
  */
enum RouteSide:
  case Left, Right

/** An enemy type's fixed tuning. `baseSpeed` is in cells per second. Every enemy
  * of one type has the same stats wherever it appears; tougher versions are separate
  * types (variants) linked by [[promotesTo]], so balancing can move units up the ladder
  * instead of changing stats. [[onDeath]] spawns enemies where this one dies;
  * [[boss]] marks the boss of a level. [[leakCost]] is how many lives it takes when
  * it gets through, so bigger units hurt more.
  */
case class EnemySpec(
    name: String,
    baseHp: Float,
    baseSpeed: Float,
    color: Color,
    size: Float,
    resist: Resistances,
    aura: Option[Aura],
    heavy: Boolean = false,
    sidePreference: Option[RouteSide] = None,
    killReward: Float = 1f,
    speedVariance: Float = 0f,
    boss: Boolean = false,
    onDeath: Option[(String, Int)] = None,
    promotesTo: Option[String] = None,
    leakCost: Int = 1
)

/** The window is always `boardPx` square. Each level's grid divides it, so bigger
  * grids mean smaller cells. `gridSize` is the default when neither level nor biome sets one.
  */
case class BoardConfig(gridSize: Int, boardPx: Float, panelPx: Float)

/** "Ambient only" global illumination: a single uniform light tint + intensity
  * that scales the field's lit brightness. White at intensity 1 is a no-op.
  */
case class LightingConfig(ambient: Color, ambientIntensity: Float)

/** A soft radial darkening at the screen edges (focus / depth). */
case class VignetteConfig(color: Color, strength: Float, radius: Float)

/** Burst of small particles when a shot lands. */
case class SparksConfig(color: Color, life: Float, speed: Float, size: Float, count: Int)

/** Soft drop shadow under each tower for grounding. */
case class ShadowConfig(color: Color, alpha: Float, offsetX: Float, offsetY: Float, scale: Float)

/** Camera kick when a life is lost. */
case class ShakeConfig(magnitude: Float, duration: Float)

/** All post/render effects, every value from config. */
case class EffectsConfig(
    vignette: VignetteConfig,
    sparks: SparksConfig,
    shadow: ShadowConfig,
    shake: ShakeConfig
)

/** I18N tuning: the active language and whether debug code-display starts on. */
case class I18nConfig(lang: String, showCodes: Boolean)

/** `startGold` is the default gold at the start of every level; a level can set
  * its own. Gold does not carry over between levels.
  */
case class EconomyConfig(startGold: Int, startLives: Int, killReward: Int)

/** Pre-run loadout: which towers the player may bring, how many at most, and
  * the default pick. Towers outside `unlocked`/`locked` are rejected at load
  * time so the picker always shows the full, current tower roster.
  */
case class LoadoutConfig(
    maxSelected: Int,
    defaultSelected: Vector[String],
    unlocked: Vector[String],
    locked: Vector[String],
    unlockCosts: Map[String, Int] = Map.empty
)

/** Out-of-run meta-progression economy: how a finished run converts progress
  * into permanent currency.
  */
case class MetaConfig(payoutPerLevel: Int, payoutPerWave: Int, payoutWinBonus: Int)

/** Between-level draft tuning: how many offers, what a reroll costs, how the
  * cost grows per reroll, and how many rounds of offers are pre-rolled.
  */
case class DraftConfig(offerCount: Int, rerollCost: Int, rerollCostGrowth: Int, poolRounds: Int)

case class CombatConfig(
    maxStepDt: Float,
    fastForward: Int,
    hitRadius: Float,
    burnDps: Float,
    burnDuration: Float,
    slowFactor: Float,
    slowDuration: Float,
    breakTime: Float,
    studyHits: Int,
    puddleShockMult: Float = 1.5f
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

/** A ground patch that amplifies shock damage while an enemy stands in it. Centre
  * and radius are in cells.
  */
case class Puddle(x: Float, y: Float, radius: Float)

/** One level: a named route plus its own explicit wave table. `gridSize` (cells per
  * side) overrides the biome and board sizes when set.
  */
case class LevelConfig(
    name: String,
    waves: Vector[WaveSpec],
    route: Route,
    gridSize: Option[Int] = None,
    puddles: Vector[Puddle] = Vector.empty,
    startGold: Option[Int] = None
)

/** A fork's alternative lane. It leaves the trunk at waypoint index [[from]]
  * and re-joins it at [[to]] (`from < to`); the lane itself runs
  * waypoints(from) -> waypoints -> waypoints(to). [[side]] labels which side of
  * the fork this lane is; the trunk segment it bypasses is the other side.
  */
case class Branch(side: RouteSide, from: Int, to: Int, waypoints: Vector[GridPos])

/** The walkable route, authored as corner waypoints. Path cells and the
  * polyline(s) enemies walk are derived from these by [[Grid]]. A route forks
  * where a [[Branch]] detours off the trunk between two waypoint indices.
  */
case class Route(waypoints: Vector[GridPos], branches: Vector[Branch] = Vector.empty)

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
    dimText: Color,
    shotShock: Color = Color(0.5f, 0.85f, 1f, 1f)
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
  *
  * A biome may override the board layout and palette for its own map. When any
  * of these are None/empty the global config values are used, so only biomes
  * that want a distinct map (the tutorial) need to specify them. `gridSize`
  * changes the walkable board; `levels` replaces the global level table; the
  * color overrides tint the board (background / path / build / grid line).
  */
case class Biome(
    id: String,
    name: String,
    roster: Vector[String],
    environment: Option[Effect],
    resistTheme: ResistTheme,
    gridSize: Option[Int] = None,
    levels: Vector[LevelConfig] = Vector.empty,
    background: Option[Color] = None,
    path: Option[Color] = None,
    build: Option[Color] = None,
    gridLine: Option[Color] = None
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
    meta: MetaConfig,
    combat: CombatConfig,
    draft: DraftConfig,
    i18n: I18nConfig,
    towers: Vector[TowerSpec],
    enemies: Vector[EnemySpec],
    levels: Vector[LevelConfig],
    loadout: LoadoutConfig,
    colors: Colors,
    lighting: LightingConfig,
    effects: EffectsConfig,
    biomes: Vector[Biome],
    twists: Vector[Twist],
    relics: Vector[Relic],
    augments: Vector[Augment],
    talents: Vector[Talent]
)
