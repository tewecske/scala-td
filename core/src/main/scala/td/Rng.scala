package td

/** Immutable xorshift64* generator. No global mutable state: every call returns
  * the next value alongside the advanced generator, so a run can be seeded and
  * replayed deterministically.
  */
case class Rng(state: Long):
  def nextLong(): (Long, Rng) =
    var x = state
    x ^= x >>> 12
    x ^= x << 25
    x ^= x >>> 27
    (x * 0x2545F4914F6CDD1DL, new Rng(x))

  def nextInt(bound: Int): (Int, Rng) =
    if bound <= 0 then throw IllegalArgumentException(s"bound must be > 0, got $bound")
    val (n, r) = nextLong()
    ((n & Long.MaxValue) % bound).toInt -> r

  def nextFloat(): (Float, Rng) =
    val (n, r) = nextLong()
    ((n >>> 40).toFloat) / (1 << 24) -> r

  def nextBoolean(): (Boolean, Rng) =
    val (n, r) = nextLong()
    (n < 0) -> r

  def split(): (Rng, Rng) =
    val (a, r1) = nextLong()
    val (b, _)  = r1.nextLong()
    (new Rng(a), new Rng(b))

object Rng:
  private val ZeroSeedFix = 0x9E3779B97F4A7C15L

  def apply(seed: Long): Rng = new Rng(if seed == 0L then ZeroSeedFix else seed)
