package td

import com.badlogic.gdx.graphics.Color

/** How much of each damage kind a type shrugs off. Each value is the fraction
  * removed, so 0f means no resistance and 1f means immune.
  */
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

enum AuraKind:
  case SpeedBoost, Armor

/** A passive radiating from a buffing enemy to nearby allies. [[magnitude]] is
  * a +fraction for speed (0.25 = +25%) or a damage-reduction fraction for armor
  * (0.3 = 30% less incoming damage).
  */
case class Aura(kind: AuraKind, radius: Float, magnitude: Float)

/** What a projectile does when it lands. */
enum DamageKind:
  case Kinetic, Fire, Slow, Splash, Explosion

/** An enemy is located by how far it has walked along the route, not by a tile.
  * That makes movement smooth, "furthest along" targeting a `maxBy`, and a leak
  * simply `progress >= Grid.length(level)`. The type is a spec name, resolved
  * against the active config so a reload re-tunes enemies already on the field.
  * [[level]] pins the enemy to the route it was spawned on (routes differ per
  * level).
  */
case class Enemy(
    id: Long,
    progress: Float,
    hp: Float,
    maxHp: Float,
    speed: Float,
    tpe: String,
    level: Int,
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
  def resist: Resistances  = spec.resist
  def effectiveSpeed: Float = (if slowed then speed * slowFactor else speed) * speedMult

case class Tower(cell: GridPos, cooldown: Float, tpe: String):
  def spec: TowerSpec = Config.towerSpec(tpe)

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
    level: Int,
    towers: Vector[Tower],
    enemies: Vector[Enemy],
    shots: Vector[Projectile],
    gold: Int,
    lives: Int,
    wave: Int,
    spawnQueue: Vector[String],
    spawnTimer: Float,
    breakTimer: Float,
    nextId: Long,
    won: Boolean = false
):
  def isOver: Boolean   = lives <= 0
  def inBreak: Boolean  = breakTimer > 0f

/** Player intent for one step. Kept as data so the simulation stays pure and
  * later roguelite choices (pick an upgrade, sell a tower) are just new cases.
  */
enum Command:
  case Build(cell: GridPos, tpe: String)

/** Which screen the game is on. */
enum Phase:
  case Menu
  case Playing(w: World)
  case Paused(w: World)
  case GameOver(level: Int, wave: Int)
  case Victory(level: Int, lives: Int, gold: Int)
