package td

class MessagesTest extends munit.FunSuite:
  test("en messages load and resolve") {
    Messages.showCodes = false
    assertEquals(Messages.t("notice.showCodes", "on"), "show codes: on")
  }

  test("missing code falls back to the code") {
    Messages.showCodes = false
    assertEquals(Messages.t("no.such.code"), "no.such.code")
  }

  test("showCodes returns the code instead of the text") {
    val prev = Messages.showCodes
    Messages.showCodes = true
    try assertEquals(Messages.t("notice.showCodes", "on"), "notice.showCodes")
    finally Messages.showCodes = prev
  }

  test("printf args interpolate") {
    Messages.showCodes = false
    assertEquals(Messages.t("notice.showCodes", "off"), "show codes: off")
  }
