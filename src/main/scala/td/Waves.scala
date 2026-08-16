package td

/** Difficulty curve. Every wave parameter is a pure function of the wave number,
  * which keeps balancing to one file and makes runs reproducible.
  */
object Waves:
  /** Pause between waves, in seconds. Also the grace period before wave 1. */
  val BreakTime = 4f

  def count(wave: Int): Int = 5 + wave * 2

  /** Enemy hp is the type baseline times this growth factor. */
  def hpScale(wave: Int): Float = math.pow(1.17, wave - 1).toFloat

  def spawnInterval(wave: Int): Float = math.max(0.32f, 0.85f - wave * 0.02f)

  /** Which enemy types can appear this wave, with relative weights. Earlier
    * waves are basic; resisters/tanks unlock next, buffing commanders last. A
    * later "levels" task will replace this with explicit per-wave compositions.
    */
  def enemyTypes(wave: Int): Vector[(EnemyType, Int)] =
    val early = Vector(EnemyType.Grunt -> 8, EnemyType.Swift -> 3)
    val mid   = if wave >= 3 then Vector(EnemyType.Ember -> 3, EnemyType.Frostling -> 3, EnemyType.Brute -> 2) else Vector.empty
    val late  = if wave >= 6 then Vector(EnemyType.Commander -> 2, EnemyType.Shieldbearer -> 2) else Vector.empty
    early ++ mid ++ late
