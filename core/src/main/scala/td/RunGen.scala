package td

/** Rolls a full run from a seed, once, before any step runs. The result is
  * snapshotted onto RunContext so Sim.step only reads it (never re-rolls).
  */
object RunGen:

  /** How many twists a real run gets. Tunable placeholder; move to config if
    * the designer wants a difficulty curve.
    */
  private val TwistsPerRun = 1

  /** Roll a real run: a non-tutorial biome (its resist theme + roster) and one
    * twist. Deterministic: same seed => identical RunContext.
    */
  def roll(seed: Long): RunContext =
    val (biomeRng, twistRng) = Rng(seed).split()
    val biome = pickBiome(biomeRng)
    RunContext(
      seed = seed,
      biome = biome.id,
      resistTheme = biome.resistTheme,
      twists = pickTwists(twistRng),
      roster = biome.roster
    )

  /** The fixed tutorial: always the "tutorial" biome, no twists, its own roster.
    * Identical gameplay for every seed; only the echoed seed differs.
    */
  def tutorial(seed: Long = RunContext.DefaultSeed): RunContext =
    val biome = Config.biome("tutorial")
    RunContext(
      seed = seed,
      biome = biome.id,
      resistTheme = biome.resistTheme,
      twists = Vector.empty,
      roster = biome.roster
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
