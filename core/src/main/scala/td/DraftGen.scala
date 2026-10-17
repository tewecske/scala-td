package td

/** Pre-rolls the between-level draft pool once per run, filtered by the
  * player's owned talents. Pure: same seed + talents => identical pool.
  */
object DraftGen:
  /** Fixed seed for the tutorial's pool so the tutorial stays reproducible. */
  val TutorialPoolSeed: Long = 0xD27AF5EEDL

  /** Boon effects the simulation implements. The config also holds placeholder
    * boons with no behavior yet; they stay out of the draft until they are built.
    */
  val ImplementedEffects: Set[String] = Set("squareDeal", "boxOffice", "sharpenedEdge", "vertigo")

  def implemented(e: Effect): Boolean = ImplementedEffects.contains(e.kind)

  /** Relics the player can actually roll: common (no requirement) plus any
    * rare/legendary whose required deep talent they own; implemented ones only.
    */
  def eligibleRelics(talents: Vector[String]): Vector[Relic] =
    val owned = Meta.effects(talents)
    Config.relics.filter(r => implemented(r.effect) && r.requiredTalent.forall(owned.owns))

  /** Augments that can be offered: every implemented one (augments are ungated). */
  def eligibleAugments: Vector[Augment] = Config.augments.filter(a => implemented(a.effect))

  /** The full offer pool: eligible relics + eligible augments. */
  def eligibleItems(talents: Vector[String]): Vector[DraftOffer] =
    eligibleRelics(talents).map(r => DraftOffer.Relic(r.id)) ++
      eligibleAugments.map(a => DraftOffer.Augment(a.id))

  /** Roll `DraftPoolRounds * OfferCount` offers. Offers within a round are
    * distinct; repeats across rounds are possible and are guarded at pick time.
    */
  def rollPool(rng: Rng, talents: Vector[String]): Vector[DraftOffer] =
    val items = eligibleItems(talents)
    if items.isEmpty then Vector.empty
    else
      val n = Config.OfferCount
      (0 until Config.DraftPoolRounds).foldLeft((Vector.empty[DraftOffer], rng)) {
        case ((acc, r), _) =>
          val (round, r2) = rollRound(items, r, n)
          (acc ++ round, r2)
      }._1

  private def rollRound(items: Vector[DraftOffer], rng: Rng, n: Int): (Vector[DraftOffer], Rng) =
    val idx = items.indices.toArray
    var r   = rng
    var i   = 0
    while i < n && i < idx.length do
      val (j, r2) = r.nextInt(idx.length - i)
      val k       = i + j
      val tmp     = idx(i); idx(i) = idx(k); idx(k) = tmp
      r = r2
      i += 1
    (idx.take(n).map(items(_)).toVector, r)
