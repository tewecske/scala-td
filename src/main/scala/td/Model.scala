package td

import com.badlogic.gdx.graphics.Color

/** An enemy is located by how far it has walked along the route, not by a tile.
  * That makes movement smooth, "furthest along" targeting a `maxBy`, and a leak
  * simply `progress >= Grid.length`.
  */
case class Enemy(
    id: Long,
    progress: Float,
    hp: Float,
    maxHp: Float,
    speed: Float,
    slowUntil: Float = 0f,
    slowFactor: Float = 1f,
    burnDps: Float = 0f,
    burnUntil: Float = 0f
):
  def pos: (Float, Float) = Grid.positionAt(progress)
  def slowed: Boolean     = slowUntil > 0f
  def burning: Boolean    = burnUntil > 0f
  def effectiveSpeed: Float = if slowed then speed * slowFactor else speed

case class Tower(cell: GridPos, cooldown: Float, tpe: TowerType)

/** What a projectile does when it lands. */
enum DamageKind:
  case Kinetic, Fire, Slow, Splash, Explosion

/** A buildable tower. Colour is a field but defined in [[Config]] so tuning and
  * palette stay in one file.
  */
enum TowerType(
    val name: String,
    val cost: Int,
    val color: Color,
    val range: Float,
    val fireInterval: Float,
    val damage: Float,
    val projectileSpeed: Float,
    val kind: DamageKind,
    val splashRadius: Float
):
  case Basic extends TowerType("Basic", 50, Config.TowerColor, 3.2f * Config.CellPx, 0.55f, 12f, 420f, DamageKind.Kinetic, 0f)
  case Fire extends TowerType("Fire", 80, Config.TowerFireColor, 2.8f * Config.CellPx, 0.50f, 8f, 480f, DamageKind.Fire, 0f)
  case Frost extends TowerType("Frost", 70, Config.TowerFrostColor, 3.0f * Config.CellPx, 0.60f, 5f, 440f, DamageKind.Slow, 0f)
  case Cannon extends TowerType("Cannon", 100, Config.TowerSplashColor, 3.4f * Config.CellPx, 0.80f, 10f, 400f, DamageKind.Splash, 1.2f * Config.CellPx)
  case Bombard extends TowerType("Bombard", 140, Config.TowerBoomColor, 4.0f * Config.CellPx, 1.10f, 22f, 320f, DamageKind.Explosion, 1.6f * Config.CellPx)

/** A shot in flight. [[dest]] is set only for explosion projectiles, which fly
  * to a fixed world point instead of homing at a live target.
  */
case class Projectile(
    x: Float,
    y: Float,
    targetId: Long,
    kind: DamageKind,
    damage: Float,
    speed: Float,
    splashRadius: Float,
    dest: Option[(Float, Float)]
):
  def homes: Boolean = kind != DamageKind.Explosion

/** The whole mutable-feeling game, as one immutable value. Everything that can
  * change during a run lives here so a run can be snapshotted, replayed, or
  * seeded.
  */
case class World(
    towers: Vector[Tower],
    enemies: Vector[Enemy],
    shots: Vector[Projectile],
    gold: Int,
    lives: Int,
    wave: Int,
    spawnQueue: Int,
    spawnTimer: Float,
    breakTimer: Float,
    nextId: Long,
    rng: Long
):
  def isOver: Boolean   = lives <= 0
  def inBreak: Boolean  = breakTimer > 0f

/** Player intent for one step. Kept as data so the simulation stays pure and
  * later roguelite choices (pick an upgrade, sell a tower) are just new cases.
  */
enum Command:
  case Build(cell: GridPos, tpe: TowerType)

/** Which screen the game is on. */
enum Phase:
  case Menu
  case Playing(w: World)
  case GameOver(waveReached: Int)
