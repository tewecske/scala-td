package td

/** An enemy is located by how far it has walked along the route, not by a tile.
  * That makes movement smooth, "furthest along" targeting a `maxBy`, and a leak
  * simply `progress >= Grid.length`.
  */
case class Enemy(id: Long, progress: Float, hp: Float, maxHp: Float, speed: Float):
  def pos: (Float, Float) = Grid.positionAt(progress)

case class Tower(cell: GridPos, cooldown: Float)

case class Projectile(x: Float, y: Float, targetId: Long, damage: Float)

/** The whole mutable-feeling game, as one immutable value. Everything that can
  * change during a run lives here so a run can be snapshotted, replayed, or
  * seeded.
  */
case class World(
    towers: Vector[Tower],
    enemies: Vector[Enemy],
    shots: Vector[Projectile],
    gold: Int,
    lives: Int,
    wave: Int,
    spawnQueue: Int,
    spawnTimer: Float,
    breakTimer: Float,
    nextId: Long,
    rng: Long
):
  def isOver: Boolean   = lives <= 0
  def inBreak: Boolean  = breakTimer > 0f

/** Player intent for one step. Kept as data so the simulation stays pure and
  * later roguelite choices (pick an upgrade, sell a tower) are just new cases.
  */
enum Command:
  case Build(cell: GridPos)

/** Which screen the game is on. */
enum Phase:
  case Menu
  case Playing(w: World)
  case GameOver(waveReached: Int)
