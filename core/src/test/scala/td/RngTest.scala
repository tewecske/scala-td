package td

class RngTest extends TestBase:
  private val seeds = Seq(0x5EEDL, 1L, 42L, 0L)

  test("same seed yields identical nextLong sequences") {
    seeds.foreach { s =>
      val r0 = Rng(s)
      val r1 = Rng(s)
      assertEquals(r0.nextLong(), r1.nextLong())
      assertEquals(r0.nextLong(), r1.nextLong())
    }
  }

  test("different seeds yield different sequences") {
    val (a, _) = Rng(1L).nextLong()
    val (b, _) = Rng(2L).nextLong()
    assertNotEquals(a, b)
  }

  test("seed 0 is normalized to a fixed nonzero state") {
    val r = Rng(0L)
    assert(r.state != 0L)
    assertEquals(Rng(0L), Rng(0L))
    assertEquals(r.nextLong(), Rng(0L).nextLong())
  }

  test("nextInt stays within bounds") {
    var r = Rng(42L)
    for _ <- 0 until 1000 do
      val (v, n) = r.nextInt(7)
      assert(v >= 0 && v < 7)
      r = n
  }

  test("nextFloat is in [0, 1)") {
    var r = Rng(42L)
    for _ <- 0 until 1000 do
      val (v, n) = r.nextFloat()
      assert(v >= 0f && v < 1f)
      r = n
  }

  test("split produces independent streams") {
    val (a, b) = Rng(42L).split()
    assertNotEquals(a.state, b.state)
    assertNotEquals(a.nextLong(), b.nextLong())
  }

  test("sim is deterministic for a fixed seed") {
    seeds.foreach { s =>
      val a = Sim.initial(s)
      val b = Sim.initial(s)
      assertEquals(a, b)
      var wa = a
      var wb = b
      for _ <- 0 until 10 do
        wa = Sim.step(wa, 0.05f, Vector.empty)
        wb = Sim.step(wb, 0.05f, Vector.empty)
      assertEquals(wa, wb)
    }
  }

  test("different seeds differ in run.seed but still step") {
    val a = Sim.initial(1L)
    val b = Sim.initial(2L)
    assertNotEquals(a.run.seed, b.run.seed)
    val wa = (0 until 10).foldLeft(a)((w, _) => Sim.step(w, 0.05f, Vector.empty))
    assertEquals(wa.run.seed, 1L)
  }
