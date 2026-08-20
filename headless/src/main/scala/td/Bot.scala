package td

/** Minimal autoplay so a headless sim isn't empty. Not part of the real game's
  * input path — it only fabricates [[Command]]s for [[Sim.step]]: build towers
  * during play, socket a held augment, and pick the first draft offer between
  * levels so a headless run can keep progressing.
  */
object Bot:

  def step(world: World): Vector[Command] =
    world.draft match
      case Some(d) => pickOffer(d.offers)
      case None =>
        if world.pendingAugment.isDefined then
          firstSocketable(world) match
            case Some(cell) => Vector(Command.AttachAugment(cell))
            case None       => buildCommand(world)
        else buildCommand(world)

  private def buildCommand(world: World): Vector[Command] =
    val cheapest = Config.game.towers.minBy(_.cost)
    if world.gold < cheapest.cost then Vector.empty
    else firstBuildable(world, cheapest.name) match
      case Some(cell) => Vector(Command.Build(cell, cheapest.name))
      case None       => Vector.empty

  private def pickOffer(offers: Vector[DraftOffer]): Vector[Command] =
    offers.headOption match
      case Some(DraftOffer.Relic(id))   => Vector(Command.PickRelic(id))
      case Some(DraftOffer.Augment(id)) => Vector(Command.PickAugment(id))
      case None                         => Vector.empty

  private def firstSocketable(world: World): Option[GridPos] =
    world.towers.find(t => Sim.canAttachAugment(world, t.cell)).map(_.cell)

  private def firstBuildable(world: World, tpe: String): Option[GridPos] =
    val size = Grid.Size
    (0 until size).iterator
      .flatMap(y => (0 until size).iterator.map(GridPos(_, y)))
      .find(Sim.canBuild(world, _, tpe))
