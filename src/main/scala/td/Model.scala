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

/** The fixed profile of an enemy archetype. hp/speed are the wave-1 baselines
  * (hp is scaled by [[Waves.hpScale]]); resistances and the optional aura come
  * straight from here.
  */
enum EnemyType(
    val name: String,
    val baseHp: Float,
    val baseSpeed: Float,
    val color: Color,
    val size: Float,
    val resist: Resistances,
    val aura: Option[Aura]
):
  case Grunt   extends EnemyType("Grunt", 22f, 42f, Config.EnemyColor, 16f, Resistances(), None)
  case Swift   extends EnemyType("Swift", 10f, 70f, Config.EnemySwiftColor, 12f, Resistances(), None)
  case Brute   extends EnemyType("Brute", 90f, 24f, Config.EnemyBruteColor, 26f, Resistances(kinetic = 0.35f), None)
  case Ember   extends EnemyType("Ember", 26f, 40f, Config.EnemyEmberColor, 16f, Resistances(fire = 0.6f), None)
  case Frostling extends EnemyType("Frostling", 24f, 42f, Config.EnemyFrostlingColor, 16f, Resistances(slow = 0.7f), None)
  case Commander extends EnemyType("Commander", 60f, 30f, Config.EnemyCommanderColor, 18f, Resistances(), Some(Aura(AuraKind.SpeedBoost, 2.5f * Config.CellPx, 0.25f)))
  case Shieldbearer extends EnemyType("Shieldbearer", 55f, 32f, Config.EnemyShieldbearerColor, 18f, Resistances(kinetic = 0.2f), Some(Aura(AuraKind.Armor, 2.0f * Config.CellPx, 0.3f)))

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
    tpe: EnemyType,
    slowUntil: Float = 0f,
    slowFactor: Float = 1f,
    burnDps: Float = 0f,
    burnUntil: Float = 0f,
    speedMult: Float = 1f,
    armor: Float = 0f
):
  def pos: (Float, Float) = Grid.positionAt(progress)
  def slowed: Boolean     = slowUntil > 0f
  def burning: Boolean    = burnUntil > 0f
  def buffed: Boolean     = speedMult > 1f || armor > 0f
  def resist: Resistances = tpe.resist
  def effectiveSpeed: Float = (if slowed then speed * slowFactor else speed) * speedMult

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
