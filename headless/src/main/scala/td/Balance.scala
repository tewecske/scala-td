package td

/** Retunes the game against the explorer, trained from scratch each time it is
  * measured so earlier rounds never leak into later ones. There are two passes:
  *
  *  - global: every enemy type's health and speed move by one shared factor,
  *    until the explorer's mean win rate over all levels lands in a band. Stats
  *    stay fixed per type, so a type plays the same on every level.
  *  - local (per level): only the wave makeup changes. Harder means promoting
  *    part of each wave up its variant ladder (Crate -> Pallet -> Crate Captain)
  *    and adding enemies; easier means demoting and removing them. Bosses are
  *    never touched.
  *
  * Both passes stop when the measurement is in the band, at the round cap, or
  * when the direction flips (the band lies between two steps). On a flip the
  * easier of the two tunings is kept, so a level is never left unwinnable.
  */
object Balance:

  enum Verdict:
    case TooEasy, TooHard, Ok

  /** How far one local step moves a level: `promoteShare` of a wave's chosen unit
    * changes rung, and the wave grows or shrinks by `resizeShare` (at least one).
    */
  /** `keepLength`: a resized wave keeps its length, so added units come with shorter
    * gaps between spawns (a denser wave) and removed ones with longer gaps.
    */
  final case class Steps(promoteShare: Double, resizeShare: Double, keepLength: Boolean = false):
    def describe: String =
      s"${"%.0f".format(promoteShare * 100)}% of a unit changes rung, waves resize by ${"%.0f".format(resizeShare * 100)}%" +
        (if keepLength then ", keeping their length" else "")

  /** The shortest gap between two spawns a resized wave can get. */
  val MinSpawnInterval = 0.1f

  /** One global step multiplies (or divides) every enemy's health and speed. */
  final case class GlobalSteps(hp: Double, speed: Double)

  final case class Settings(
      rounds: Int,
      batches: Int,
      batchSize: Int,
      evalGames: Int,
      easyWin: Double,
      easyLives: Double,
      hardWin: Double,
      steps: Steps,
      seed: Long,
      think: Int,
      biome: String,
      towers: Vector[String]
  )

  /** Too easy: wins most of the time and loses few lives in the games it wins. */
  def tooEasy(s: Stats, easyWin: Double, easyLives: Double): Boolean =
    s.winRate >= easyWin && s.livesLostWon <= easyLives

  def verdict(s: Stats, set: Settings): Verdict =
    if tooEasy(s, set.easyWin, set.easyLives) then Verdict.TooEasy
    else if s.winRate < set.hardWin then Verdict.TooHard
    else Verdict.Ok

  /** The level list a biome plays: its own levels if it has any, else the global ones. */
  def levelsOf(cfg: GameConfig, biome: String): Vector[LevelConfig] =
    cfg.biomes.find(_.id == biome).filter(_.levels.nonEmpty).map(_.levels).getOrElse(cfg.levels)

  private def withLevels(cfg: GameConfig, biome: String, levels: Vector[LevelConfig]): GameConfig =
    if cfg.biomes.exists(b => b.id == biome && b.levels.nonEmpty) then
      cfg.copy(biomes = cfg.biomes.map(b => if b.id == biome then b.copy(levels = levels) else b))
    else cfg.copy(levels = levels)

  // -- local steps --------------------------------------------------------------

  /** One step harder for `level` (0-based): per wave, part of the lowest promotable
    * unit moves up a rung and every non-boss type grows by its share.
    */
  def harden(cfg: GameConfig, s: Steps, biome: String, level: Int): (GameConfig, String) =
    reshape(cfg, s, biome, level, harder = true)

  /** One step easier for `level`: per wave, part of the highest demotable unit
    * moves down a rung and every non-boss type shrinks by its share (keeping at
    * least one of each).
    */
  def ease(cfg: GameConfig, s: Steps, biome: String, level: Int): (GameConfig, String) =
    reshape(cfg, s, biome, level, harder = false)

  private def reshape(cfg: GameConfig, s: Steps, biome: String, level: Int, harder: Boolean): (GameConfig, String) =
    val all     = levelsOf(cfg, biome)
    val lvl     = all(level)
    val byName  = cfg.enemies.map(e => e.name -> e).toMap
    val demotes = cfg.enemies.flatMap(e => e.promotesTo.map(_ -> e.name)).toMap
    def isBoss(n: String) = byName.get(n).exists(_.boss)
    def next(n: String): Option[String] =
      if isBoss(n) then None else if harder then byName.get(n).flatMap(_.promotesTo) else demotes.get(n)
    val log = Vector.newBuilder[String]

    val waves = lvl.waves.zipWithIndex.map { case (w, wi) =>
      // Harder lifts the lowest rung present; easier drops the highest one. Hardening
      // never touches the first wave, so it stays small and plain.
      val movable =
        if harder && wi == 0 then Vector.empty
        else w.composition.zipWithIndex.filter { case ((n, c), _) => c > 0 && next(n).isDefined }
      val pick =
        if movable.isEmpty then None
        else if harder then Some(movable.minBy { case ((n, _), _) => rung(n, demotes) }._2)
        else Some(movable.maxBy { case ((n, _), _) => rung(n, demotes) }._2)
      val moved = pick.fold(w.composition) { idx =>
        val (name, count) = w.composition(idx)
        val to = next(name).get
        val k  = math.min(count, math.max(1, math.round(count * s.promoteShare).toInt))
        log += s"wave ${wi + 1}: $k $name -> $to"
        add(w.composition.updated(idx, (name, count - k)), to, k)
      }
      val nonBoss =
        if harder && wi == 0 then Vector.empty // the first wave stays as generated
        else moved.filter { case (n, c) => c > 0 && !isBoss(n) }
      // Every non-boss type changes by its own share (at least one unit), so a wave
      // keeps its mix; easing never takes a type below one unit.
      val resized = nonBoss.foldLeft(moved) { case (comp, (name, count)) =>
        val step   = math.max(1, math.round(count * s.resizeShare).toInt)
        val change = if harder then step else -math.min(step, count - 1)
        if change != 0 then log += s"wave ${wi + 1}: ${if change > 0 then "+" else ""}$change $name"
        add(comp, name, change)
      }
      val comp  = resized.filter(_._2 > 0)
      val count = comp.map(_._2).sum
      val interval =
        if !s.keepLength || count == w.count then w.spawnInterval
        else
          val gap = math.max(MinSpawnInterval, math.round(w.spawnInterval * w.count / count * 1000f) / 1000f)
          if gap != w.spawnInterval then log += f"wave ${wi + 1}: gap ${w.spawnInterval}%.2fs -> $gap%.2fs"
          gap
      w.copy(count = count, spawnInterval = interval, composition = comp)
    }

    val next0 = withLevels(cfg, biome, all.updated(level, lvl.copy(waves = waves)))
    val lines = log.result()
    (next0, if lines.isEmpty then "nothing left to change" else lines.mkString("; "))

  /** Position on a variant ladder: 0 for a base type, +1 per promotion. */
  private def rung(name: String, demotes: Map[String, String]): Int =
    Iterator.iterate(Option(name))(_.flatMap(demotes.get)).takeWhile(_.isDefined).size - 1

  private def add(comp: Vector[(String, Int)], name: String, k: Int): Vector[(String, Int)] =
    if comp.exists(_._1 == name) then comp.map { case (n, c) => if n == name then (n, c + k) else (n, c) }
    else comp :+ (name, k)

  // -- global steps -------------------------------------------------------------

  /** Every enemy's health and speed times the step factors (or divided, when easing). */
  def scaleEnemies(cfg: GameConfig, g: GlobalSteps, harder: Boolean): GameConfig =
    val (hp, sp) = if harder then (g.hp, g.speed) else (1 / g.hp, 1 / g.speed)
    cfg.copy(enemies = cfg.enemies.map(e =>
      e.copy(baseHp = (e.baseHp * hp).toFloat, baseSpeed = (e.baseSpeed * sp).toFloat)))

  // -- search -------------------------------------------------------------------

  /** Trains a fresh explorer on `level` under `cfg` and measures it greedily. */
  def measure(cfg: GameConfig, s: Settings, level: Int, sink: Training.Sink, log: String => Unit): Stats =
    Config.use(cfg)
    val start = Knowledge(route = KnowledgeStore.routeKey(s.biome, level))
    val k     = Training.train(start, s.batches, s.batchSize, s.seed, s.think, s.biome, level, s.towers, sink, log)
    val games = Training.evaluate(k, s.evalGames, s.seed + 1000000L, s.think, s.biome, level, s.towers)
    games.foreach(g => sink("test", level, g))
    Stats.of(games.map(_.outcome))

  /** Steps `start` towards [[Verdict.Ok]]. `judge` measures a tuning; `step` moves it
    * one step harder (true) or easier (false). Returns the tuning to keep.
    */
  def search(
      start: GameConfig, rounds: Int, label: String,
      judge: GameConfig => Verdict, step: (GameConfig, Boolean) => (GameConfig, String),
      log: String => Unit
  ): GameConfig =
    var cfg     = start
    var prev    = Option.empty[(GameConfig, Verdict)]
    var result  = Option.empty[GameConfig]
    var round   = 0
    while result.isEmpty && round < rounds do
      round += 1
      val v = judge(cfg)
      prev match
        case Some((before, pv)) if pv != Verdict.Ok && v != Verdict.Ok && pv != v =>
          val easier = if v == Verdict.TooHard then before else cfg
          log(s"$label round $round  ${v.toString.toLowerCase} after ${pv.toString.toLowerCase}: " +
            "the target lies between two steps, keeping the easier one")
          result = Some(easier)
        case _ =>
          v match
            case Verdict.Ok =>
              log(s"$label round $round  balanced")
              result = Some(cfg)
            case _ =>
              val harder = v == Verdict.TooEasy
              val (next, summary) = step(cfg, harder)
              log(s"$label round $round  ${if harder then "too easy -> harder" else "too hard -> easier"}: $summary")
              prev = Some((cfg, v))
              cfg = next
    result.getOrElse {
      // Round cap: the last step was never measured, so keep the last measured tuning
      // unless it was too hard, in which case the untested easier step is the better bet.
      log(s"$label hit the round cap")
      prev.fold(cfg)((before, pv) => if pv == Verdict.TooEasy then before else cfg)
    }

  /** Balances one level by wave makeup only. */
  def run(start: GameConfig, s: Settings, level: Int, sink: Training.Sink, log: String => Unit): GameConfig =
    val label = s"level ${level + 1}"
    search(start, s.rounds, label,
      cfg =>
        log(s"$label  training a fresh explorer, ${s.batches} batches")
        val stats = measure(cfg, s, level, sink, _ => ())
        log(s"$label  ${stats.line}")
        verdict(stats, s),
      (cfg, harder) => if harder then harden(cfg, s.steps, s.biome, level) else ease(cfg, s.steps, s.biome, level),
      log)

  /** Balances each listed level in turn, each starting from the previous result. */
  def runAll(start: GameConfig, s: Settings, levels: Vector[Int], sink: Training.Sink, log: String => Unit): GameConfig =
    levels.foldLeft(start)((cfg, level) => run(cfg, s, level, sink, log))

  /** Global pass: scales every enemy's health and speed until the mean win rate
    * over `levels` is between `s.hardWin` and `s.easyWin`.
    */
  def runGlobal(
      start: GameConfig, s: Settings, g: GlobalSteps, levels: Vector[Int],
      sink: Training.Sink, log: String => Unit
  ): GameConfig =
    var scale = 1.0
    search(start, s.rounds, "global",
      cfg =>
        val rates = levels.map(l => l -> measure(cfg, s, l, sink, _ => ()).winRate)
        val mean  = rates.map(_._2).sum / rates.size
        log(s"global  enemy hp/speed scale ${"%.2f".format(scale)}  mean win ${"%.0f".format(mean * 100)}%  " +
          rates.map { case (l, r) => s"L${l + 1}=${"%.0f".format(r * 100)}%" }.mkString(" "))
        if mean >= s.easyWin then Verdict.TooEasy else if mean < s.hardWin then Verdict.TooHard else Verdict.Ok,
      (cfg, harder) =>
        scale = if harder then scale * g.hp else scale / g.hp
        (scaleEnemies(cfg, g, harder),
          s"enemy health x${"%.2f".format(if harder then g.hp else 1 / g.hp)}, speed x${"%.2f".format(if harder then g.speed else 1 / g.speed)}"),
      log)
