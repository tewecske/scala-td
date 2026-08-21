package td

/** Rolls a full run from a seed, once, before any step runs. The result is
  * snapshotted onto RunContext so Sim.step only reads it (never re-rolls).
  */
object RunGen:

  /** How many twists a real run gets. Tunable placeholder; move to config if
    * the designer wants a difficulty curve.
    */
  private val TwistsPerRun = 1

  /** Pre-roll one speed multiplier per enemy type that opts into variance, once
   * at run start. `Sim.spawn` reads it; the value is `1 + variance * rand` in
   * [1, 1+variance], so a "faster runner" stays an upgrade but plays out a
   * little differently every run. Deterministic per seed.
   */
  private def rollSpeedMults(rng: Rng): Map[String, Float] =
    Config.game.enemies
      .filter(_.speedVariance > 0f)
      .foldLeft((Map.empty[String, Float], rng)) { case ((acc, r), e) =>
        val (x, r2) = r.nextFloat()
        (acc + (e.name -> (1f + e.speedVariance * x)), r2)
      }._1

  /** Pre-roll one lane choice per enemy expected across the whole run. An enemy
   * reads the value for its id (modulo) at the first fork it meets, so `Sim`
   * never consumes RNG inside a step. Wraps safely if swarm-multiplied waves
   * exceed the estimate.
   */
  private def rollLaneChoices(rng: Rng): Vector[Long] =
    val n = math.max(1, Config.levels.map(_.waves.map(_.count).sum).sum)
    (0 until n).foldLeft((Vector.empty[Long], rng)) { case ((acc, r), _) =>
      val (v, r2) = r.nextLong()
      (acc :+ (v & Long.MaxValue), r2)
    }._1

  /** Roll a real run: a non-tutorial biome (its resist theme + roster) and one
    * twist. Deterministic: same seed => identical RunContext.
    */
  def roll(seed: Long, talents: Vector[String] = Vector.empty): RunContext =
    val (biomeRng, twistRng) = Rng(seed).split()
    val (draftRng, laneRng)  = twistRng.split()
    val biome = pickBiome(biomeRng)
    val (speedRng, laneRng2) = laneRng.split()
    RunContext(
      seed = seed,
      biome = biome.id,
      resistTheme = biome.resistTheme,
      twists = pickTwists(twistRng),
      roster = biome.roster,
      talents = talents,
      draftPool = DraftGen.rollPool(draftRng, talents),
      laneChoices = rollLaneChoices(laneRng2),
      speedMults = rollSpeedMults(speedRng)
    )

  /** The fixed tutorial: always the "tutorial" biome, no twists, its own roster.
    * Identical gameplay for every seed; only the echoed seed differs.
    */
  def tutorial(seed: Long = RunContext.DefaultSeed, talents: Vector[String] = Vector.empty): RunContext =
    val biome = Config.biome("tutorial")
    RunContext(
      seed = seed,
      biome = biome.id,
      resistTheme = biome.resistTheme,
      twists = Vector.empty,
      roster = biome.roster,
      talents = talents,
      draftPool = DraftGen.rollPool(Rng(DraftGen.TutorialPoolSeed), talents),
      laneChoices = rollLaneChoices(Rng(DraftGen.TutorialPoolSeed))
    )

  private def pickBiome(rng: Rng): Biome =
    val real = Config.biomes.filter(_.id != "tutorial")
    if real.isEmpty then Config.biome("tutorial")
    else
      val (idx, _) = rng.nextInt(real.size)
      real(idx)

  private def pickTwists(rng: Rng): Vector[String] =
    val all = Config.twists
    if all.isEmpty then Vector.empty
    else
      val (idx, _) = rng.nextInt(all.size)
      Vector(all(idx).id)
