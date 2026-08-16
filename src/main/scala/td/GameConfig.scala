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
  * (hp is scaled by [[Waves.hpScale]]); resistances and the optional aura come
  * straight from here.
  */
case class EnemySpec(
    name: String,
    baseHp: Float,
    baseSpeed: Float,
    color: Color,
    size: Float,
    resist: Resistances,
    aura: Option[Aura]
)

case class BoardConfig(gridSize: Int, cellPx: Float, panelPx: Float)

case class EconomyConfig(startGold: Int, startLives: Int, killReward: Int)

case class CombatConfig(
    maxStepDt: Float,
    fastForward: Int,
    hitRadius: Float,
    burnDps: Float,
    burnDuration: Float,
    slowFactor: Float,
    slowDuration: Float
)

/** From [[fromWave]] onward, these enemy types (by name) join the spawn table
  * with the given relative weights. Later entries pile onto earlier ones.
  */
case class CompositionEntry(fromWave: Int, weights: Vector[(String, Int)])

case class WaveConfig(
    breakTime: Float,
    hpScaleBase: Float,
    hpScaleExp: Float,
    countBase: Int,
    countPerWave: Int,
    spawnIntervalStart: Float,
    spawnIntervalDecay: Float,
    spawnIntervalMin: Float,
    composition: Vector[CompositionEntry]
)

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

/** The whole tuning set, immutable, one value per loaded config. */
case class GameConfig(
    board: BoardConfig,
    economy: EconomyConfig,
    combat: CombatConfig,
    towers: Vector[TowerSpec],
    enemies: Vector[EnemySpec],
    waves: WaveConfig,
    route: Route,
    colors: Colors
)
