package td

/** Wave pacing, all read from the active config. With per-level wave tables the
  * only formula left is the level/wave lookup; every wave parameter lives in its
  * own [[WaveSpec]], which keeps balancing to one file and makes runs
  * reproducible (composition is explicit, so spawning is deterministic without
  * an rng).
  */
object Waves:
  /** Pause between waves, in seconds. Also the grace period before wave 1 of a
    * level.
    */
  def breakTime: Float = Config.BreakTime

  /** Number of waves in a level. */
  def waveCount(level: Int): Int = Config.levelAt(level).waves.size

  /** The spec for (level, 1-indexed wave), clamped defensively so a mid-run
    * config reload can never index out of bounds.
    */
  def spec(level: Int, wave: Int): WaveSpec =
    val ws = Config.levelAt(level).waves
    ws(math.max(0, math.min(wave - 1, ws.size - 1)))

  /** Concrete spawn order for a wave: each type repeated its count, in listed
    * order (grouped, not interleaved). Deterministic and easy to reason about.
    */
  def spawnOrder(spec: WaveSpec): Vector[String] =
    spec.composition.flatMap { case (name, n) => Vector.fill(n)(name) }
