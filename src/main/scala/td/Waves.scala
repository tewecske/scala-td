package td

/** Difficulty curve. Every wave parameter is a pure function of the wave number,
  * which keeps balancing to one file and makes runs reproducible.
  */
object Waves:
  /** Pause between waves, in seconds. Also the grace period before wave 1. */
  val BreakTime = 4f

  def count(wave: Int): Int = 5 + wave * 2

  def hp(wave: Int): Float = 20f * math.pow(1.17, wave - 1).toFloat

  /** World units per second; one cell is Config.CellPx units. */
  def speed(wave: Int): Float = 42f + wave * 1.4f

  def spawnInterval(wave: Int): Float = math.max(0.32f, 0.85f - wave * 0.02f)
