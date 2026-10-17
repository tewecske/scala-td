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
    explosion: Float = 0f,
    shock: Float = 0f
):
  def apply(kind: DamageKind): Float = kind match
    case DamageKind.Kinetic   => kinetic
    case DamageKind.Fire      => fire
    case DamageKind.Slow      => slow
    case DamageKind.Splash    => splash
    case DamageKind.Explosion => explosion
    case DamageKind.Shock     => shock

  def updated(kind: DamageKind, v: Float): Resistances = kind match
    case DamageKind.Kinetic   => copy(kinetic = v)
    case DamageKind.Fire      => copy(fire = v)
    case DamageKind.Slow      => copy(slow = v)
    case DamageKind.Splash    => copy(splash = v)
    case DamageKind.Explosion => copy(explosion = v)
    case DamageKind.Shock     => copy(shock = v)

  /** Base resist plus the run's resist theme, clamped to [0, 1] (1 = immune).
    * Only the primary/secondary kinds gain; all others pass through untouched.
    */
  def layered(theme: ResistTheme): Resistances =
    def addFor(kind: DamageKind): Float =
      if theme.primary == kind then theme.primaryAmount
      else if theme.secondary == kind then theme.secondaryAmount
      else 0f
    def c(base: Float, kind: DamageKind): Float =
      math.min(1f, math.max(-1f, base + addFor(kind)))
    Resistances(
      c(kinetic, DamageKind.Kinetic),
      c(fire, DamageKind.Fire),
      c(slow, DamageKind.Slow),
      c(splash, DamageKind.Splash),
      c(explosion, DamageKind.Explosion),
      c(shock, DamageKind.Shock)
    )

enum AuraKind:
  case SpeedBoost, Armor, TowerChill

/** A passive radiating from a buffing enemy to nearby allies. [[magnitude]] is
  * a +fraction for speed (0.25 = +25%) or a damage-reduction fraction for armor
  * (0.3 = 30% less incoming damage).
  */
case class Aura(kind: AuraKind, radius: Float, magnitude: Float)

/** What a projectile does when it lands. */
enum DamageKind:
  case Kinetic, Fire, Slow, Splash, Explosion, Shock

/** An enemy is located by how far it has walked along the route, not by a tile.
  * That makes movement smooth, "furthest along" targeting a `maxBy`, and a leak
  * simply `progress >= Grid.length(level)`. The type is a spec name, resolved
  * against the active config so a reload re-tunes enemies already on the field.
  * [[level]] pins the enemy to the route it was spawned on (routes differ per
  * level). [[lane]] is the branch it took at a fork (None = trunk/not yet
  * forked), read by [[Grid.positionAt]].
  */
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
    armor: Float = 0f,
    lane: Option[Int] = None
):
  def spec: EnemySpec      = Config.enemySpec(tpe)
  def pos: (Float, Float)  = Grid.positionAt(progress, level, lane)
  def slowed: Boolean      = slowUntil > 0f
  def burning: Boolean     = burnUntil > 0f
  def buffed: Boolean      = speedMult > 1f || armor > 0f
  def effectiveSpeed: Float = (if slowed then speed * slowFactor else speed) * speedMult

case class Tower(cell: GridPos, cooldown: Float, tpe: String, augment: Option[String] = None, shotsFired: Int = 0):
  def spec: TowerSpec = Config.towerSpec(tpe)

/** A shot in flight. [[dest]] is set only for explosion projectiles, which fly
  * to a fixed world point instead of homing at a live target. [[slow]] carries
  * an augment-supplied slow override (vertigo), or None for the default. The
  * remaining fields carry twist-supplied modifiers from the firing tower.
  */
case class Projectile(
    x: Float,
    y: Float,
    targetId: Long,
    kind: DamageKind,
    damage: Float,
    speed: Float,
    splashRadius: Float,
    dest: Option[(Float, Float)],
    slow: Option[(Float, Float)] = None,
    burnTickRate: Float = 1f,
    noBurnStack: Boolean = false,
    armorPierce: Boolean = false,
    bounce: Boolean = false,
    bounceRadius: Float = 0f,
    crit: Boolean = false
):
  def homes: Boolean = kind != DamageKind.Explosion

/** A temporary ground hazard (a dead heavy's wreckage) that slows enemies moving
  * through it. [[remaining]] counts down each step.
  */
case class Hazard(x: Float, y: Float, radius: Float, slowFactor: Float, remaining: Float)

/** One thing the between-level draft can offer: a global relic or a tower-socket
  * augment.
  */
enum DraftOffer:
  case Relic(id: String)
  case Augment(id: String)

/** The between-level draft: the offers on the table and the current gold cost
  * to reroll them (grows with each reroll).
  */
case class Draft(offers: Vector[DraftOffer], rerollCost: Int)

/** The whole mutable-feeling game, as one immutable value. Everything that can
  * change during a run lives here so a run can be snapshotted, replayed, or
  * seeded.
  */
object RunContext:
  val DefaultSeed: Long = 0x5EEDL

/** The seed and player-selected run modifiers that shape one playthrough. */
case class RunContext(
    seed: Long,
    biome: String = "front-lawn",
    resistTheme: ResistTheme = ResistTheme(DamageKind.Kinetic, 0f, DamageKind.Fire, 0f),
    twists: Vector[String] = Vector.empty,
    roster: Vector[String] = Vector.empty,
    mods: Vector[String] = Vector.empty,
    loadout: Vector[String] = Vector.empty,
    relics: Vector[String] = Vector.empty,
    augments: Vector[String] = Vector.empty,
    talents: Vector[String] = Vector.empty,
    draftPool: Vector[DraftOffer] = Vector.empty,
    laneChoices: Vector[Long] = Vector.empty,
    speedMults: Map[String, Float] = Map.empty
  )

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
    run: RunContext,
    draft: Option[Draft] = None,
    draftCursor: Int = 0,
    pendingAugment: Option[String] = None,
    won: Boolean = false,
    kills: Int = 0,
    leaks: Int = 0,
    hazards: Vector[Hazard] = Vector.empty,
    leakDamage: Float = 0f,
    events: Vector[DiscoveryEvent] = Vector.empty,
    traitHits: Map[(String, String), Int] = Map.empty,
    hitFeedback: Vector[HitFeedback] = Vector.empty,
    nameEvents: Vector[NameEvent] = Vector.empty,
    seen: Set[String] = Set.empty,
    revealed: Set[String] = Set.empty,
    leaksByType: Map[String, Int] = Map.empty
):
  def isOver: Boolean   = lives <= 0
  def inBreak: Boolean  = breakTimer > 0f

  /** The biome this level is played in: every real run's first level is in the
    * starter biome, the rest in the biome the run rolled.
    */
  def biome: String = Config.biomeAt(run.biome, level)

  /** The resist layer enemies spawn with on this level. */
  def resistTheme: ResistTheme =
    if biome == run.biome then run.resistTheme else Config.biome(biome).resistTheme

/** Player intent for one step. Kept as data so the simulation stays pure and
  * later roguelite choices (pick an upgrade, sell a tower) are just new cases.
  */
enum Command:
  case Build(cell: GridPos, tpe: String)
  case PickRelic(relicId: String)
  case PickAugment(augmentId: String)
  case AttachAugment(cell: GridPos)
  case Reroll()
  case GrantGold(amount: Int)
  case UnlockTowers()

/** Which screen the game is on. */
enum Phase:
  case Menu
  case Briefing(w: World)
  case Loadout(run: RunContext, selected: Vector[String], meta: MetaData)
  case Talents(data: MetaData)
  case ProgressView(rows: Vector[ProgressLog.Summary], page: Int)
  case ReplayList(files: Vector[(String, Replay)], page: Int)
  case Replaying(playback: Playback)
  case Playing(w: World)
  case Paused(w: World)
  /** Display settings being edited; `back` is the screen to return to. */
  case Settings(draft: DisplaySettings, back: Phase)
  case Recap(
      won: Boolean,
      level: Int,
      wave: Int,
      lives: Int,
      gold: Int,
      newEntries: Vector[JournalEntry],
      knownEntries: Vector[JournalEntry],
      death: Option[DeathRecap]
  )

/** How precisely a trait is known. UNKNOWN is the absence of a journal entry. */
enum Precision:
  case Sighting, Studied

/** A discovery surfaced from Sim during a step. [[value]] is the vague hint at
  * Sighting ("resists"/"neutral"/...), or the exact value at Studied ("0.35").
  */
case class DiscoveryEvent(enemy: String, traitKey: String, tier: Precision, value: String)

/** A landed hit surfaced to the renderer for a damage/resist popup. [[dealt]] is
  * the post-resist/armor damage; [[resistFrac]] is the fraction of incoming
  * damage removed by resistance (0 none, >= 1 immune).
  */
case class HitFeedback(x: Float, y: Float, kind: DamageKind, dealt: Float, resistFrac: Float, crit: Boolean = false)

/** A name flash: an enemy type was first seen this run (`reveal=false`, drawn as
  * "???" until its name is known) or its name was first learned by a killing blow
  * (`reveal=true`).
  */
case class NameEvent(enemy: String, x: Float, y: Float, reveal: Boolean)

/** A one-line cause-of-death summary computed from leaks + the run's loadout. */
case class DeathRecap(
    resistedKind: DamageKind,
    leakPct: Int,
    counterKind: DamageKind,
    missingCounter: Boolean
)

/** How an enemy type is drawn. Purely visual and chosen per type in the config,
  * deliberately not tied to abilities: several types share a shape and differ by
  * size and color.
  */
enum EnemyShape(val sides: Int, val radiusScale: Float, val turn: Float):
  case Square   extends EnemyShape(4, 0.707f, 45f)
  case Diamond  extends EnemyShape(4, 0.74f, 0f)
  case Triangle extends EnemyShape(3, 0.78f, 0f)
  case Pentagon extends EnemyShape(5, 0.66f, 0f)
  case Hexagon  extends EnemyShape(6, 0.62f, 0f)
  case Octagon  extends EnemyShape(8, 0.58f, 22.5f)

  /** Corners of this shape for an enemy of `size`, centered on the origin, the
    * first corner pointing along `heading` (degrees) so it faces where it walks.
    */
  def corners(size: Float, heading: Float = 0f): Vector[(Float, Float)] =
    val r = size * radiusScale
    (0 until sides).toVector.map { i =>
      val a = math.toRadians(heading + turn + i * 360.0 / sides)
      ((math.cos(a) * r).toFloat, (math.sin(a) * r).toFloat)
    }
