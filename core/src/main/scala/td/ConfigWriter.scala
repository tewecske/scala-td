package td

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.{JsonValue, JsonWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.Try

/** Serializes a [[GameConfig]] back to the JSON shape [[ConfigLoader]] parses,
  * so the editor writes exactly what the game loads. Built as a libGDX
  * `JsonValue` tree (the loader's own representation) and pretty-printed.
  */
object ConfigWriter:

  def toJson(cfg: GameConfig): String =
    root(cfg).prettyPrint(JsonWriter.OutputType.json, 2)

  def write(cfg: GameConfig, path: String): Either[String, Unit] =
    Try(Files.writeString(Paths.get(path), toJson(cfg) + "\n", StandardCharsets.UTF_8))
      .toEither.map(_ => ()).left.map(_.getMessage)

  /** `Color` -> `#RRGGBB` (or `#RRGGBBAA` when alpha != 1). Inverse of
    * [[ConfigLoader.parseColor]]. Round-trips exactly: channels are 0..255.
    */
  def colorHex(c: Color): String =
    def ch(v: Float): Int = math.round(v * 255f).max(0).min(255)
    val (r, g, b, a) = (ch(c.r), ch(c.g), ch(c.b), ch(c.a))
    if a == 255 then f"#$r%02X$g%02X$b%02X" else f"#$r%02X$g%02X$b%02X$a%02X"

  // -- tree builders -----------------------------------------------------------

  private def obj(fields: (String, JsonValue)*): JsonValue =
    val o = JsonValue(JsonValue.ValueType.`object`)
    fields.foreach { (k, v) => o.addChild(k, v) }
    o

  private def arr(items: Seq[JsonValue]): JsonValue =
    val a = JsonValue(JsonValue.ValueType.array)
    items.foreach(a.addChild(_))
    a

  private def str(s: String): JsonValue = JsonValue(s)
  private def bool(b: Boolean): JsonValue = JsonValue(b)
  private def num(i: Int): JsonValue = JsonValue(i.toLong)
  private def num(f: Float): JsonValue = JsonValue(java.lang.Float.toString(f).toDouble)
  private def nullV: JsonValue = JsonValue(JsonValue.ValueType.nullValue)

  // -- sections ----------------------------------------------------------------

  private def root(cfg: GameConfig): JsonValue =
    obj(
      "board"    -> board(cfg.board),
      "economy"  -> economy(cfg.economy),
      "meta"     -> meta(cfg.meta),
      "draft"    -> draft(cfg.draft),
      "combat"   -> combat(cfg.combat),
      "i18n"     -> i18n(cfg.i18n),
      "towers"   -> arr(cfg.towers.map(tower(cfg, _))),
      "enemies"  -> arr(cfg.enemies.map(enemy(cfg, _))),
      "levels"   -> arr(cfg.levels.map(level)),
      "loadout"  -> loadout(cfg.loadout),
      "colors"   -> colors(cfg.colors),
      "lighting" -> lighting(cfg.lighting),
      "effects"  -> effects(cfg.effects),
      "biomes"   -> arr(cfg.biomes.map(biome)),
      "twists"   -> arr(cfg.twists.map(twist)),
      "relics"   -> arr(cfg.relics.map(relic)),
      "augments" -> arr(cfg.augments.map(augment)),
      "talents"  -> arr(cfg.talents.map(talent))
    )

  private def board(b: BoardConfig): JsonValue =
    obj("gridSize" -> num(b.gridSize), "boardPx" -> num(b.boardPx), "panelPx" -> num(b.panelPx))

  private def economy(e: EconomyConfig): JsonValue =
    obj("startGold" -> num(e.startGold), "startLives" -> num(e.startLives),
        "killReward" -> num(e.killReward))

  private def meta(m: MetaConfig): JsonValue =
    obj("payoutPerLevel" -> num(m.payoutPerLevel), "payoutPerWave" -> num(m.payoutPerWave),
        "payoutWinBonus" -> num(m.payoutWinBonus))

  private def draft(d: DraftConfig): JsonValue =
    obj("offerCount" -> num(d.offerCount), "rerollCost" -> num(d.rerollCost),
        "rerollCostGrowth" -> num(d.rerollCostGrowth), "poolRounds" -> num(d.poolRounds))

  private def combat(c: CombatConfig): JsonValue =
    obj(
      "maxStepDt"   -> num(c.maxStepDt),
      "fastForward" -> num(c.fastForward),
      "hitRadius"   -> num(c.hitRadius),
      "breakTime"   -> num(c.breakTime),
      "studyHits"   -> num(c.studyHits),
      "critMult"    -> num(c.critMult),
      "immuneAt"    -> num(c.immuneAt)
    )

  private def i18n(i: I18nConfig): JsonValue =
    obj("lang" -> str(i.lang), "showCodes" -> bool(i.showCodes))

  private def loadout(l: LoadoutConfig): JsonValue =
    obj(
      "maxSelected"     -> num(l.maxSelected),
      "defaultSelected" -> arr(l.defaultSelected.map(str)),
      "unlocked"        -> arr(l.unlocked.map(str)),
      "locked"          -> arr(l.locked.map(str)),
      "unlockCosts"     -> obj(l.locked.flatMap(t => l.unlockCosts.get(t).map(c => t -> num(c)))*)
    )

  private def tower(cfg: GameConfig, t: TowerSpec): JsonValue =
    obj(
      "name"            -> str(t.name),
      "cost"            -> num(t.cost),
      "color"           -> str(colorHex(t.color)),
      "range"           -> num(t.range),
      "fireInterval"    -> num(t.fireInterval),
      "damage"          -> num(t.damage),
      "projectileSpeed" -> num(t.projectileSpeed),
      "kind"            -> str(t.kind.toString),
      "splashRadius"    -> num(t.splashRadius),
      "targeting"       -> str(t.targeting.toString),
      "burnDps"         -> num(t.burnDps),
      "burnDuration"    -> num(t.burnDuration),
      "slowFactor"      -> num(t.slowFactor),
      "slowDuration"    -> num(t.slowDuration),
      "groundTarget"    -> bool(t.groundTarget),
      "puddleMult"      -> num(t.puddleMult)
    )

  private def enemy(cfg: GameConfig, e: EnemySpec): JsonValue =
    val aura = e.aura.map(a => obj(
      "kind" -> str(a.kind.toString), "radius" -> num(a.radius), "magnitude" -> num(a.magnitude)
    )).getOrElse(nullV)
    val onDeath = e.onDeath.map { case (n, c) => obj("enemy" -> str(n), "count" -> num(c)) }.getOrElse(nullV)
    val promotes = e.promotesTo.map(str).getOrElse(nullV)
    val side = e.sidePreference.map(s => str(s.toString)).getOrElse(nullV)
    obj(
      "name"     -> str(e.name),
      "baseHp"   -> num(e.baseHp),
      "baseSpeed" -> num(e.baseSpeed),
      "color"    -> str(colorHex(e.color)),
      "size"     -> num(e.size),
      "resist"   -> obj(
        "kinetic" -> num(e.resist.kinetic), "fire" -> num(e.resist.fire),
        "slow" -> num(e.resist.slow), "splash" -> num(e.resist.splash),
        "explosion" -> num(e.resist.explosion), "shock" -> num(e.resist.shock)
      ),
      "aura"     -> aura,
      "heavy"    -> bool(e.heavy),
      "sidePreference" -> side,
      "killReward" -> num(e.killReward),
      "speedVariance" -> num(e.speedVariance),
      "boss" -> bool(e.boss),
      "onDeath" -> onDeath,
      "promotesTo" -> promotes,
      "leakCost" -> num(e.leakCost),
      "shape" -> str(e.shape.toString)
    )

  private def level(l: LevelConfig): JsonValue =
    val size    = l.gridSize.map(g => num(g)).getOrElse(nullV)
    val puddles = arr(l.puddles.map(p => obj("x" -> num(p.x), "y" -> num(p.y), "radius" -> num(p.radius))))
    obj("name" -> str(l.name), "route" -> route(l.route),
        "waves" -> arr(l.waves.map(wave)), "gridSize" -> size, "puddles" -> puddles,
        "startGold" -> l.startGold.map(g => num(g)).getOrElse(nullV))

  private def route(r: Route): JsonValue =
    obj("waypoints" -> arr(r.waypoints.map(pos)), "branches" -> arr(r.branches.map(branch)))

  private def pos(p: GridPos): JsonValue = arr(Seq(num(p.x), num(p.y)))

  private def branch(b: Branch): JsonValue =
    obj("side" -> str(b.side.toString), "from" -> num(b.from), "to" -> num(b.to),
        "waypoints" -> arr(b.waypoints.map(pos)))

  private def wave(w: WaveSpec): JsonValue =
    obj("count" -> num(w.count), "spawnInterval" -> num(w.spawnInterval),
        "hpScale" -> num(w.hpScale), "composition" -> composition(w.composition))

  private def composition(c: Vector[(String, Int)]): JsonValue =
    val o = JsonValue(JsonValue.ValueType.`object`)
    c.foreach { (name, n) => o.addChild(name, num(n)) }
    o

  private def colors(c: Colors): JsonValue =
    obj(
      "background" -> str(colorHex(c.background)), "path" -> str(colorHex(c.path)),
      "build" -> str(colorHex(c.build)), "gridLine" -> str(colorHex(c.gridLine)),
      "enemy" -> str(colorHex(c.enemy)), "shot" -> str(colorHex(c.shot)),
      "shotFire" -> str(colorHex(c.shotFire)), "shotFrost" -> str(colorHex(c.shotFrost)),
      "shotSplash" -> str(colorHex(c.shotSplash)), "shotBoom" -> str(colorHex(c.shotBoom)),
      "burnTint" -> str(colorHex(c.burnTint)), "slowTint" -> str(colorHex(c.slowTint)),
      "buffTint" -> str(colorHex(c.buffTint)), "panel" -> str(colorHex(c.panel)),
      "hoverOk" -> str(colorHex(c.hoverOk)), "hoverBad" -> str(colorHex(c.hoverBad)),
      "range" -> str(colorHex(c.range)), "buttonFill" -> str(colorHex(c.buttonFill)),
      "hpBarBack" -> str(colorHex(c.hpBarBack)), "hpBarFill" -> str(colorHex(c.hpBarFill)),
      "text" -> str(colorHex(c.text)), "dimText" -> str(colorHex(c.dimText)),
      "shotShock" -> str(colorHex(c.shotShock))
    )

  private def lighting(l: LightingConfig): JsonValue =
    obj("ambient" -> str(colorHex(l.ambient)), "ambientIntensity" -> num(l.ambientIntensity))

  private def effects(e: EffectsConfig): JsonValue =
    obj(
      "vignette" -> vignette(e.vignette),
      "sparks"   -> sparks(e.sparks),
      "shadow"   -> shadow(e.shadow),
      "shake"    -> shake(e.shake)
    )

  private def vignette(v: VignetteConfig): JsonValue =
    obj("color" -> str(colorHex(v.color)), "strength" -> num(v.strength), "radius" -> num(v.radius))

  private def sparks(s: SparksConfig): JsonValue =
    obj("color" -> str(colorHex(s.color)), "life" -> num(s.life), "speed" -> num(s.speed),
        "size" -> num(s.size), "count" -> num(s.count))

  private def shadow(s: ShadowConfig): JsonValue =
    obj("color" -> str(colorHex(s.color)), "alpha" -> num(s.alpha),
        "offsetX" -> num(s.offsetX), "offsetY" -> num(s.offsetY), "scale" -> num(s.scale))

  private def shake(s: ShakeConfig): JsonValue =
    obj("magnitude" -> num(s.magnitude), "duration" -> num(s.duration))

  private def effect(e: Effect): JsonValue =
    obj("kind" -> str(e.kind), "params" -> params(e.params))

  private def params(m: Map[String, Float]): JsonValue =
    val o = JsonValue(JsonValue.ValueType.`object`)
    m.foreach { (k, v) => o.addChild(k, num(v)) }
    o

  private def biome(b: Biome): JsonValue =
    val env = b.environment.map(effect).getOrElse(nullV)
    val fields = Seq(
      "id" -> str(b.id),
      "name" -> str(b.name),
      "roster" -> arr(b.roster.map(str)),
      "environment" -> env,
      "resistTheme" -> obj(
        "primary" -> str(b.resistTheme.primary.toString),
        "primaryAmount" -> num(b.resistTheme.primaryAmount),
        "secondary" -> str(b.resistTheme.secondary.toString),
        "secondaryAmount" -> num(b.resistTheme.secondaryAmount))
    ) ++ b.gridSize.map(g => "gridSize" -> num(g)) ++
      (if b.levels.nonEmpty then Seq("levels" -> arr(b.levels.map(level))) else Seq.empty) ++
      b.background.map(c => "background" -> str(colorHex(c))) ++
      b.path.map(c => "path" -> str(colorHex(c))) ++
      b.build.map(c => "build" -> str(colorHex(c))) ++
      b.gridLine.map(c => "gridLine" -> str(colorHex(c))) ++
      (if b.starter then Seq("starter" -> JsonValue(true)) else Seq.empty)
    obj(fields*)

  private def twist(t: Twist): JsonValue =
    obj("id" -> str(t.id), "name" -> str(t.name),
        "targetTower" -> str(t.targetTower), "effect" -> effect(t.effect))

  private def relic(r: Relic): JsonValue =
    val req = r.requiredTalent.map(str).getOrElse(nullV)
    obj("id" -> str(r.id), "name" -> str(r.name), "rarity" -> str(r.rarity.toString),
        "requiredTalent" -> req, "effect" -> effect(r.effect))

  private def augment(a: Augment): JsonValue =
    obj("id" -> str(a.id), "name" -> str(a.name), "effect" -> effect(a.effect))

  private def talent(t: Talent): JsonValue =
    obj("id" -> str(t.id), "name" -> str(t.name), "layer" -> str(t.layer.toString),
        "cost" -> num(t.cost), "prereqs" -> arr(t.prereqs.map(str)),
        "effect" -> effect(t.effect))