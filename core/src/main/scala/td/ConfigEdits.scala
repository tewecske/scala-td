package td

/** Pure edits on a [[GameConfig]] for the editor: they keep references
  * consistent (renaming an enemy renames it everywhere it is used).
  */
object ConfigEdits:

  /** A list of levels the editor can show: the shared campaign levels, or a
    * biome's own levels (the tutorial).
    */
  enum LevelSet:
    case Campaign
    case OfBiome(id: String)

  def levelSets(cfg: GameConfig): Vector[LevelSet] =
    LevelSet.Campaign +: cfg.biomes.filter(_.levels.nonEmpty).map(b => LevelSet.OfBiome(b.id))

  def levels(cfg: GameConfig, set: LevelSet): Vector[LevelConfig] = set match
    case LevelSet.Campaign    => cfg.levels
    case LevelSet.OfBiome(id) => cfg.biomes.find(_.id == id).fold(Vector.empty)(_.levels)

  def withLevels(cfg: GameConfig, set: LevelSet, ls: Vector[LevelConfig]): GameConfig = set match
    case LevelSet.Campaign    => cfg.copy(levels = ls)
    case LevelSet.OfBiome(id) => cfg.copy(biomes = cfg.biomes.map(b => if b.id == id then b.copy(levels = ls) else b))

  def updateLevel(cfg: GameConfig, set: LevelSet, i: Int)(f: LevelConfig => LevelConfig): GameConfig =
    val ls = levels(cfg, set)
    if i < 0 || i >= ls.size then cfg else withLevels(cfg, set, ls.updated(i, f(ls(i))))

  def updateEnemy(cfg: GameConfig, name: String)(f: EnemySpec => EnemySpec): GameConfig =
    cfg.copy(enemies = cfg.enemies.map(e => if e.name == name then f(e) else e))

  def updateBiome(cfg: GameConfig, id: String)(f: Biome => Biome): GameConfig =
    cfg.copy(biomes = cfg.biomes.map(b => if b.id == id then f(b) else b))

  /** The environment rules [[Sim]] knows, each with its parameters' starting values. */
  val EnvironmentRules: Vector[Effect] = Vector(
    Effect("openFloorPlan", Map("armorPerNeighbor" -> 0.15f, "radius" -> 1.5f)),
    Effect("crushed", Map("hazardDuration" -> 3f, "slowFactor" -> 0.4f, "radius" -> 1f)),
    Effect("recycling", Map("livesLossMult" -> 0.5f, "killGoldBonus" -> 1f, "swarmSizeMult" -> 1.4f)))

  /** The talent effect kinds [[Meta]] applies, each with its parameters' starting values. */
  val TalentEffectKinds: Vector[Effect] = Vector(
    Effect("damageBonus", Map("mult" -> 0.1f)),
    Effect("rangeBonus", Map("mult" -> 0.1f)),
    Effect("fireRateBonus", Map("mult" -> 0.1f)),
    Effect("critChance", Map("chance" -> 0.1f)),
    Effect("critDamage", Map("mult" -> 0.5f)),
    Effect("kindDamageBonus", DamageKind.values.map(k => k.toString.toLowerCase -> 0f).toMap.updated("kinetic", 0.25f)),
    Effect("shred", Map("perHit" -> 0.03f, "max" -> 0.3f)),
    Effect("goldBonus", Map("mult" -> 0.15f)),
    Effect("extraLives", Map("lives" -> 1f)),
    Effect("startGold", Map("gold" -> 30f)),
    Effect("loadoutSlots", Map("slots" -> 1f)),
    Effect("reroll", Map.empty))

  def updateTalent(cfg: GameConfig, id: String)(f: Talent => Talent): GameConfig =
    cfg.copy(talents = cfg.talents.map(t => if t.id == id then f(t) else t))

  /** What still needs a talent: other talents that require it and relics gated on it. */
  def talentUses(cfg: GameConfig, id: String): Vector[String] =
    cfg.talents.collect { case t if t.prereqs.contains(id) => s"talent ${t.name} requires it" } ++
      cfg.relics.collect { case r if r.requiredTalent.contains(id) => s"relic ${r.name} needs it" }

  /** An id for a new talent, from its name ("Quick Reload" -> "quick-reload"). */
  def freshTalentId(cfg: GameConfig, name: String): String =
    val stem  = name.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
    val taken = cfg.talents.map(_.id).toSet
    if !taken(stem) then stem else Iterator.from(2).map(i => s"$stem-$i").find(i => !taken(i)).get

  def updateTower(cfg: GameConfig, name: String)(f: TowerSpec => TowerSpec): GameConfig =
    cfg.copy(towers = cfg.towers.map(t => if t.name == name then f(t) else t))

  /** Renames a tower type and every reference to it (loadout lists, unlock costs, twists). */
  def renameTower(cfg: GameConfig, from: String, to: String): GameConfig =
    def r(n: String) = if n == from then to else n
    val l = cfg.loadout
    cfg.copy(
      towers = cfg.towers.map(t => t.copy(name = r(t.name))),
      loadout = l.copy(defaultSelected = l.defaultSelected.map(r), unlocked = l.unlocked.map(r), locked = l.locked.map(r),
        unlockCosts = l.unlockCosts.map((n, c) => r(n) -> c)),
      twists = cfg.twists.map(t => t.copy(targetTower = r(t.targetTower))))

  /** How a tower becomes available to the player. */
  enum TowerAccess:
    case Starting, Unlocked, Locked

  def towerAccess(cfg: GameConfig, name: String): TowerAccess =
    if cfg.loadout.defaultSelected.contains(name) then TowerAccess.Starting
    else if cfg.loadout.locked.contains(name) then TowerAccess.Locked
    else TowerAccess.Unlocked

  /** Moves a tower between "in the default loadout", "free to pick" and "bought with meta currency". */
  def setTowerAccess(cfg: GameConfig, name: String, access: TowerAccess, unlockCost: Int = 30): GameConfig =
    val l = cfg.loadout
    def without(v: Vector[String]) = v.filterNot(_ == name)
    val next = access match
      case TowerAccess.Starting =>
        l.copy(defaultSelected = without(l.defaultSelected) :+ name, unlocked = without(l.unlocked) :+ name,
          locked = without(l.locked), unlockCosts = l.unlockCosts - name)
      case TowerAccess.Unlocked =>
        l.copy(defaultSelected = without(l.defaultSelected), unlocked = without(l.unlocked) :+ name,
          locked = without(l.locked), unlockCosts = l.unlockCosts - name)
      case TowerAccess.Locked =>
        l.copy(defaultSelected = without(l.defaultSelected), unlocked = without(l.unlocked),
          locked = without(l.locked) :+ name, unlockCosts = l.unlockCosts.updated(name, l.unlockCosts.getOrElse(name, unlockCost)))
    cfg.copy(loadout = next)

  /** A wave with `count` matching its composition (the loader requires it). */
  def wave(spawnInterval: Float, hpScale: Float, composition: Vector[(String, Int)]): WaveSpec =
    val comp = composition.filter(_._2 > 0)
    WaveSpec(comp.map(_._2).sum, spawnInterval, hpScale, comp)

  /** Every place an enemy type is used, as readable lines. */
  def uses(cfg: GameConfig, name: String): Vector[String] =
    val inWaves = levelSets(cfg).flatMap { set =>
      levels(cfg, set).zipWithIndex.flatMap { (l, li) =>
        l.waves.zipWithIndex.collect { case (w, wi) if w.composition.exists(_._1 == name) =>
          s"${setName(set)} level ${li + 1} (${l.name}), wave ${wi + 1}"
        }
      }
    }
    val inEnemies = cfg.enemies.collect {
      case e if e.promotesTo.contains(name)            => s"${e.name} promotes to it"
      case e if e.onDeath.exists(_._1 == name)         => s"${e.name} spawns it on death"
    }
    val inRosters = cfg.biomes.collect { case b if b.roster.contains(name) => s"roster of biome ${b.name}" }
    inWaves ++ inEnemies ++ inRosters

  def setName(set: LevelSet): String = set match
    case LevelSet.Campaign    => "campaign"
    case LevelSet.OfBiome(id) => id

  /** Renames an enemy type and every reference to it. */
  def renameEnemy(cfg: GameConfig, from: String, to: String): GameConfig =
    def r(n: String) = if n == from then to else n
    val enemies = cfg.enemies.map(e => e.copy(
      name = r(e.name),
      promotesTo = e.promotesTo.map(r),
      onDeath = e.onDeath.map((n, c) => (r(n), c))))
    def fixLevels(ls: Vector[LevelConfig]) =
      ls.map(l => l.copy(waves = l.waves.map(w => w.copy(composition = w.composition.map((n, c) => (r(n), c))))))
    cfg.copy(
      enemies = enemies,
      levels = fixLevels(cfg.levels),
      biomes = cfg.biomes.map(b => b.copy(roster = b.roster.map(r), levels = fixLevels(b.levels))))

  /** A name not yet used by any enemy type, based on `base`. */
  def freshName(cfg: GameConfig, base: String): String =
    val taken = cfg.enemies.map(_.name).toSet
    if !taken.contains(base) then base
    else Iterator.from(2).map(i => s"$base $i").find(n => !taken.contains(n)).get

  /** Removes an enemy type; refused (Left) while anything still uses it. */
  def deleteEnemy(cfg: GameConfig, name: String): Either[Vector[String], GameConfig] =
    val used = uses(cfg, name)
    if used.nonEmpty then Left(used) else Right(cfg.copy(enemies = cfg.enemies.filterNot(_.name == name)))

  /** Total hit points a wave sends, before run modifiers. */
  def waveHp(cfg: GameConfig, w: WaveSpec): Float =
    w.composition.map((n, c) => cfg.enemies.find(_.name == n).fold(0f)(_.baseHp) * c * w.hpScale).sum

  /** Lives the wave would cost if every enemy leaked. */
  def waveLeak(cfg: GameConfig, w: WaveSpec): Int =
    w.composition.map((n, c) => cfg.enemies.find(_.name == n).fold(1)(_.leakCost) * c).sum
