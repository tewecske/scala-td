TASK 01B — INTERNATIONALIZATION (I18N) PREPARATION + DEBUG CODE MODE
====================================================================

Goal
----
Set up the i18n *infrastructure* only: a config option for the active language,
a `Messages` accessor, a bundled `en` properties file, and a debug mode that
shows message CODES instead of translated text. No gameplay, and no migration
of existing UI strings — each task localizes the strings it defines.

Depends on
----------
task01 (subprojects exist; `core` is the home).

Plan refs
---------
context.md section 9 (I18N). PLAN section 3 (data-driven everything).

Decisions (locked)
------------------
- Language selection comes from config only (`config.json` -> `i18n.lang`).
- No non-ASCII for now: the built-in font has a limited glyph set.
- Debug code display starts from config (`i18n.showCodes`) and is toggled at
  runtime with Alt+I.
- All other i18n text belongs to the task that defines it (do NOT rethread
  existing `Render`/`Main` strings here).

Scope
-----
1. Config option:
   - `core/src/main/scala/td/GameConfig.scala`: add `I18nConfig(lang, showCodes)`
     and a `i18n` field on `GameConfig`.
   - `core/src/main/scala/td/ConfigLoader.scala`: parse the `i18n` object.
   - `core/src/main/resources/config.json`: add `"i18n": { "lang": "en",
     "showCodes": false }`.
2. Messages file:
   - `core/src/main/resources/messages/messages_en.properties`: `code=text`
     scaffold with the one key task01b itself needs (`notice.showCodes`).
3. `core/src/main/scala/td/Messages.scala`:
   - Load `messages_<lang>.properties` from the classpath via
     `java.util.Properties` (JDK built-in, headless-safe).
   - `lang` from `Config.game.i18n.lang`.
   - `t(code)` and `t(code, args*)` (printf-style, via `String.format`).
   - Missing code falls back to the code itself (never crashes).
   - `@volatile var showCodes` initialized from config; when true, `t` returns
     the code unchanged.
4. Debug toggle:
   - `game/src/main/scala/td/Main.scala`: Alt+I flips `Messages.showCodes` and
     shows a notice via `Messages.t("notice.showCodes", ...)`.
5. Test:
   - `core/src/test/scala/td/MessagesTest.scala`: en load/resolve, missing-code
     fallback, `showCodes` returns the code, printf interpolation.

Files
-----
- core/src/main/scala/td/GameConfig.scala (I18nConfig + field)
- core/src/main/scala/td/ConfigLoader.scala (parse i18n)
- core/src/main/resources/config.json (i18n section)
- core/src/main/resources/messages/messages_en.properties (new)
- core/src/main/scala/td/Messages.scala (new)
- game/src/main/scala/td/Main.scala (Alt+I toggle)
- core/src/test/scala/td/MessagesTest.scala (new)

Verify
------
- `sbt compile`.
- `sbt test`.
- `sbt "game/run"` — press Alt+I to toggle code display; the notice shows the
  code `notice.showCodes` when on, `show codes: off` when off.

Notes
-----
- Keep `Sim` pure: language/debug state lives in `Messages`, never on `World`.
- `Messages.t` is the single choke point — future tasks must add their strings
  as codes here, never inline.
- Future tasks should add their own keys to `messages_en.properties` (and any
  other languages) as they define strings.
