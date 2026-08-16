package td

/** Difficulty curve, all read from the active config. Every wave parameter is a
  * pure function of the wave number, which keeps balancing to one file and makes
  * runs reproducible. A later "levels" task can swap in per-level wave tables
  * without touching [[Sim]].
  */
object Waves:
  /** Pause between waves, in seconds. Also the grace period before wave 1. */
  def breakTime: Float = Config.game.waves.breakTime

  def count(wave: Int): Int =
    val w = Config.game.waves
    w.countBase + w.countPerWave * wave

  /** Enemy hp is the type baseline times this growth factor. */
  def hpScale(wave: Int): Float =
    val w = Config.game.waves
    math.pow(w.hpScaleBase, w.hpScaleExp * (wave - 1)).toFloat

  def spawnInterval(wave: Int): Float =
    val w = Config.game.waves
    math.max(w.spawnIntervalMin, w.spawnIntervalStart - w.spawnIntervalDecay * wave)

  /** Which enemy types can appear this wave, with relative weights. Composition
    * entries are listed by name in config and resolve against the active specs.
    */
  def enemyTypes(wave: Int): Vector[(EnemySpec, Int)] =
    Config.game.waves.composition
      .filter(_.fromWave <= wave)
      .flatMap(_.weights)
      .map { case (name, wt) => Config.enemySpec(name) -> wt }
