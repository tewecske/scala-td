package td

/** Minimal autoplay so a headless sim isn't empty. Not part of the real game's
  * input path — it only fabricates [[Command.Build]] for [[Sim.step]].
  */
object Bot:

  def step(world: World): Vector[Command] =
    val cheapest = Config.game.towers.minBy(_.cost)
    if world.gold < cheapest.cost then Vector.empty
    else firstBuildable(world, cheapest.name) match
      case Some(cell) => Vector(Command.Build(cell, cheapest.name))
      case None       => Vector.empty

  private def firstBuildable(world: World, tpe: String): Option[GridPos] =
    val size = Grid.Size
    (0 until size).iterator
      .flatMap(y => (0 until size).iterator.map(GridPos(_, y)))
      .find(Sim.canBuild(world, _, tpe))
