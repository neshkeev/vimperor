/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.extension.ExtensionBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The extensions ported after the first, tested for what they do rather than that they loaded.
 *
 * Each one moved from `src/main/java`, where it was a `VimExtension` on an IntelliJ extension
 * point, to `vim-engine/src/commonMain`, where it is a `@VimPlugin` function that both hosts
 * compile. The plugin keeps a two-line adapter so `set <name>` still works in IntelliJ; when the
 * plugin goes, so does the adapter, and the extension stays.
 *
 * `ReplaceWithRegisterTest` covers the first one separately because it is also the file that proves
 * the loader itself.
 */
class PortedExtensionsTest {

  private class Session(text: String, extension: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      injector.extensionLoader.enableExtension(
        ExtensionBean(extension, VsCodeExtensions.PLUGIN_ID, "init", ""),
      )
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun script(line: String) =
      injector.vimscriptExecutor.execute(line, host.editorFor(fake), VsCodeExecutionContext, skipHistory = true)

    /**
     * An ex command as a user runs one: typed, then `<CR>`.
     *
     * Not [script], which is the same command by a shorter road and stops short of the buffer. The
     * engine writes an edit into `VsCodeEditor`'s own buffer synchronously and the host flushes it
     * out to the document when the key that caused it has been handled - so a command that edits
     * has to arrive as keys, or the edit is made and never seen.
     */
    fun ex(command: String) {
      type(":$command")
      host.key(fake, "<CR>")
    }

    val content: String get() = fake.document.content
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
  }

  // ---- vim-paragraph-motion --------------------------------------------------------------------

  /**
   * The whole reason the extension exists: Vim's `}` stops only at a line with *nothing* on it, so
   * a line holding two spaces is not a boundary and the motion sails past it.
   */
  @Test
  fun `test the paragraph motion stops at a line of only whitespace`() {
    val session = Session("one\n  \ntwo\n\nthree\n", "vim-paragraph-motion")

    session.type("}")

    assertEquals(4, session.caret, "on the whitespace-only line, which plain `}` would skip")
  }

  @Test
  fun `test it goes backwards too`() {
    val session = Session("one\n  \ntwo\n", "vim-paragraph-motion")
    session.type("}")
    session.type("}")

    session.type("{")

    assertEquals(4, session.caret)
  }

  /** A count reaches an extension the same way it reaches a built-in, through `v:count1`. */
  @Test
  fun `test a count moves that many paragraphs`() {
    val session = Session("one\n  \ntwo\n  \nthree\n", "vim-paragraph-motion")

    session.type("2}")

    // "one\n" is 0..3, "  \n" is 4..6, "two\n" is 7..10, so the second whitespace-only line starts
    // at 11. IdeaVim's own ParagraphMotionTest fixes the landing point as the *start* of that line.
    assertEquals(11, session.caret, "the start of the second whitespace-only line, not the first")
  }

  /**
   * `d}` deletes `one` and leaves its newline, which looks wrong and is Vim's own rule: an
   * exclusive motion whose end lands in column 1 has that end pulled back to the end of the
   * previous line, and becomes inclusive (`:h exclusive`). So the motion stops after `one` rather
   * than after `one\n`. Asserted because it is the kind of thing a later change would "fix".
   */
  @Test
  fun `test it composes with an operator`() {
    val session = Session("one\n  \ntwo\n", "vim-paragraph-motion")

    session.type("d}")

    assertEquals("\n  \ntwo\n", session.content)
  }

  // ---- textobj-entire --------------------------------------------------------------------------

  @Test
  fun `test ae is the whole buffer`() {
    val session = Session("  one\ntwo  \n", "textobj-entire")

    session.type("dae")

    assertEquals("", session.content)
  }

  /**
   * `ie` is the same minus the surrounding whitespace, which is the half that is not `ggVG`.
   */
  @Test
  fun `test ie leaves the surrounding whitespace behind`() {
    val session = Session("\n\n  one two  \n\n", "textobj-entire")

    session.type("die")

    assertEquals("\n\n    \n\n", session.content, "the leading and trailing blanks stay")
  }

  @Test
  fun `test it composes with an operator other than delete`() {
    val session = Session("one two\n", "textobj-entire")

    session.type("gUie")

    assertEquals("ONE TWO\n", session.content)
  }

  // ---- CamelCaseMotion -------------------------------------------------------------------------

  /**
   * The whole difference from `w`: an identifier is several words, and `w` treats it as one.
   *
   * The mappings are `<Plug>CamelCaseMotion_w` and friends, bound to `,w` only when
   * `g:camelcasemotion_key` is set - the extension deliberately installs no default keys - so the
   * test drives the `<Plug>` target, which is what a user's own mapping would reach.
   */
  @Test
  fun `test the camel motion stops inside an identifier`() {
    val session = Session("getUserName rest\n", "CamelCaseMotion")

    session.type("d")
    session.host.key(session.fake, "<Plug>CamelCaseMotion_w")

    assertEquals("UserName rest\n", session.content, "`w` would have taken the whole identifier")
  }

  @Test
  fun `test it treats snake case the same way`() {
    val session = Session("get_user_name rest\n", "CamelCaseMotion")

    session.type("d")
    session.host.key(session.fake, "<Plug>CamelCaseMotion_w")

    assertEquals("user_name rest\n", session.content)
  }

  // ---- mini-ai ---------------------------------------------------------------------------------

  /**
   * The difference from Vim's own `ci(`, and the reason people install this: Vim needs the caret
   * already inside the parentheses, and this searches forward for them.
   */
  @Test
  fun `test ci paren works from outside the parentheses`() {
    val session = Session("call(one)\n", "mini-ai")

    session.type("di(")

    assertEquals("call()\n", session.content)
  }

  @Test
  fun `test the around form takes the delimiters with it`() {
    val session = Session("call(one)\n", "mini-ai")

    session.type("da(")

    assertEquals("call\n", session.content)
  }

  @Test
  fun `test it finds quotes the same way`() {
    val session = Session("say \"hello\" now\n", "mini-ai")

    session.type("di\"")

    assertEquals("say \"\" now\n", session.content)
  }

  // ---- abolish -------------------------------------------------------------------------------------

  /**
   * `vim-abolish`, and the half people actually install it for: `crs` on `getUserName` gives
   * `get_user_name`, `crc` gives it back, without retyping the word.
   */
  @Test
  fun `test the coercions recase the word under the caret`() {
    val snake = Session("val getUserName = 1\n", "abolish")
    snake.type("wcrs")
    assertEquals("val get_user_name = 1\n", snake.content, "crs")

    val camel = Session("val get_user_name = 1\n", "abolish")
    camel.type("wcrc")
    assertEquals("val getUserName = 1\n", camel.content, "crc")

    val upper = Session("val getUserName = 1\n", "abolish")
    upper.type("wcru")
    assertEquals("val GET_USER_NAME = 1\n", upper.content, "cru")

    val kebab = Session("val getUserName = 1\n", "abolish")
    kebab.type("wcr-")
    assertEquals("val get-user-name = 1\n", kebab.content, "cr-")
  }

  /**
   * The `<Plug>` half is operator-pending, so a coercion takes a motion. That path goes through
   * `opfunc` and `g@`, which is `executeNormalWithoutMapping` - one of the functions that only
   * followed the extension into the engine because `VimExtensionFacade` moved first.
   */
  @Test
  fun `test a coercion takes a motion through opfunc`() {
    val session = Session("getUserName rest\n", "abolish")

    session.host.key(session.fake, "<Plug>(abolish-coerce-snake)")
    session.type("iw")

    assertEquals("get_user_name rest\n", session.content)
  }

  /**
   * `:Subvert` is the other half: a substitution that carries the case of what it replaced, so one
   * command fixes `child`, `Child` and `CHILD` at once where `:s` would need three.
   *
   * The space after the command name is not optional - abolish parses its arguments as everything
   * past the first space, so `:S/x/y/` is not a command it recognises. That is tpope's own
   * behaviour and the plugin's tests spell it the same way.
   */
  @Test
  fun `test Subvert replaces every case variant at once`() {
    val session = Session("child Child CHILD\n", "abolish")

    session.ex("Subvert /child/adult/g")

    assertEquals("adult Adult ADULT\n", session.content)
  }

  /** `:S` is the short form, and the same handler behind it. */
  @Test
  fun `test S is an alias for Subvert`() {
    val session = Session("child Child\n", "abolish")

    session.ex("S /child/adult/g")

    assertEquals("adult Adult\n", session.content)
  }

  /**
   * Brace alternatives, which is what separates `:S` from a case-insensitive `:s`: one command
   * takes the singular and the plural, in every case, to their own replacements.
   */
  @Test
  fun `test brace alternatives pair up`() {
    val session = Session("box Box BOX boxes Boxes BOXES\n", "abolish")

    session.ex("S /box{,es}/bag{,s}/g")

    assertEquals("bag Bag BAG bags Bags BAGS\n", session.content)
  }

  // ---- targets -----------------------------------------------------------------------------------

  /**
   * `targets.vim`, and the biggest of the ported extensions at 808 lines.
   *
   * The fixture and every expectation below are transcribed from the plugin's own
   * `VimTargetsPairTest`, which took them in turn from targets.vim's golden file `test/test1.ok`.
   * That is deliberate: the point of these is not to re-derive what targets.vim does - 73 tests in
   * `src/test` already pin that down - but to show the same source producing the same answers on
   * this host, where the caret, the document and the selection are VS Code's.
   */
  private fun targets(): Session {
    val session = Session("a ( b ) ( c ) ( ( x ) ) ( e ) ( f ) g\n", "targets")
    session.type("fx")
    return session
  }

  @Test
  fun `test the four pair modifiers`() {
    assertEquals("a ( b ) ( c ) ( () ) ( e ) ( f ) g\n", targets().also { it.type("di(") }.content, "i")
    assertEquals("a ( b ) ( c ) ( (  ) ) ( e ) ( f ) g\n", targets().also { it.type("dI(") }.content, "I")
    assertEquals("a ( b ) ( c ) (  ) ( e ) ( f ) g\n", targets().also { it.type("da(") }.content, "a")
    assertEquals("a ( b ) ( c ) ( ) ( e ) ( f ) g\n", targets().also { it.type("dA(") }.content, "A")
  }

  /** `n` and `l` are the half plain Vim has no spelling for: the *next* pair, and the *last*. */
  @Test
  fun `test the next and last qualifiers`() {
    assertEquals("a ( b ) ( c ) ( ( x ) ) () ( f ) g\n", targets().also { it.type("din(") }.content, "n")
    assertEquals("a ( b ) () ( ( x ) ) ( e ) ( f ) g\n", targets().also { it.type("dil(") }.content, "l")
  }

  @Test
  fun `test a count steps outward`() {
    assertEquals("a ( b ) ( c ) () ( e ) ( f ) g\n", targets().also { it.type("d2i(") }.content)
  }

  /** `b` is "any block", which here resolves to the paren the caret is inside. */
  @Test
  fun `test the any-block trigger`() {
    assertEquals("a ( b ) ( c ) ( () ) ( e ) ( f ) g\n", targets().also { it.type("dib") }.content)
  }

  /**
   * The seeking half, and the reason to install it: Vim's `di(` needs the caret already inside the
   * parentheses, and this looks along the line in both directions.
   */
  @Test
  fun `test it seeks forward and backward along the line`() {
    val forward = Session("a ( bbbbbbbb ) c\n", "targets")
    forward.type("di(")
    assertEquals("a () c\n", forward.content, "the caret starts before the pair")

    val backward = Session("a ( bbbbbbbb ) c\n", "targets")
    backward.type("\$di(")
    assertEquals("a () c\n", backward.content, "and after it")
  }

  /**
   * Re-issuing the object in visual mode grows the selection outward, which is why the extension is
   * a class holding the last target it produced rather than a set of free functions.
   */
  @Test
  fun `test re-issuing the object in visual mode grows the selection`() {
    val session = Session("( ( ( x ) ) )\n", "targets")
    session.type("fx")

    session.type("vibibd")

    assertEquals("( () )\n", session.content, "the second `ib` stepped out one level")
  }

  // ---- textobj-user ----------------------------------------------------------------------------

  /**
   * Unlike every other extension here, this one installs no keys. It installs two Vimscript
   * functions, and the user's own `.vimrc` calls them to declare a text object - which is why the
   * tests are scripts rather than keystrokes.
   */
  @Test
  fun `test a vimrc can declare a text object of its own`() {
    val session = Session("due 2026-09-05 ok\n", "textobj-user")

    session.script(
      "call textobj#user#plugin('date', {'-': " +
        "{'pattern': '\\d\\{4}-\\d\\{2}-\\d\\{2}', 'select': ['ad', 'id']}})",
    )
    session.type("dad")

    assertEquals("due  ok\n", session.content)
  }

  /** Both keys the spec lists reach the same object; `select` is one pattern, so `i` and `a` agree. */
  @Test
  fun `test every key in the spec is mapped`() {
    val session = Session("due 2026-09-05 ok\n", "textobj-user")

    session.script(
      "call textobj#user#plugin('date', {'-': " +
        "{'pattern': '\\d\\{4}-\\d\\{2}-\\d\\{2}', 'select': ['ad', 'id']}})",
    )
    session.type("did")

    assertEquals("due  ok\n", session.content)
  }

  /**
   * A `[header, footer]` pair, which is the half `select` alone cannot express: `select-a` spans the
   * delimiters and `select-i` only what is between them.
   */
  @Test
  fun `test a header and footer pair gives an inner and an outer object`() {
    val session = Session("x BEGIN body END y\n", "textobj-user")
    val spec = "{'pattern': ['BEGIN', 'END'], 'select-a': 'ab', 'select-i': 'ib'}"

    session.script("call textobj#user#plugin('blk', {'-': $spec})")
    session.type("dib")

    assertEquals("x BEGINEND y\n", session.content)
  }

  @Test
  fun `test the outer form takes the delimiters with it`() {
    val session = Session("x BEGIN body END y\n", "textobj-user")
    val spec = "{'pattern': ['BEGIN', 'END'], 'select-a': 'ab', 'select-i': 'ib'}"

    session.script("call textobj#user#plugin('blk', {'-': $spec})")
    session.type("dab")

    assertEquals("x  y\n", session.content)
  }

  /** `move-n` is a motion rather than a text object, so it is mapped in normal mode as well. */
  @Test
  fun `test a move spec gives a motion`() {
    val session = Session("aa 2026-09-05 bb 2027-01-01\n", "textobj-user")

    session.script(
      "call textobj#user#plugin('date', {'-': " +
        "{'pattern': '\\d\\{4}-\\d\\{2}-\\d\\{2}', 'move-n': ']d'}})",
    )
    session.type("]d")

    assertEquals(3, session.caret, "the start of the first date ahead of the caret")
  }

  /**
   * `textobj#user#map` adds keys to an object that already exists, by mapping them onto the same
   * `<Plug>` name - which is the reason the interface names exist at all.
   */
  @Test
  fun `test map adds another key to an object already declared`() {
    val session = Session("due 2026-09-05 ok\n", "textobj-user")

    session.script(
      "call textobj#user#plugin('date', {'-': " +
        "{'pattern': '\\d\\{4}-\\d\\{2}-\\d\\{2}', 'select': ['ad']}})",
    )
    session.script("call textobj#user#map('date', {'-': {'select': ['aD']}})")
    session.type("daD")

    assertEquals("due  ok\n", session.content)
  }

  /**
   * Disabling has to take the two functions with it, and the loader cannot do that by owner.
   *
   * The engine tracks mappings and listeners by owner and function handlers by name, so the whole
   * of `disableExtension` used to miss these: `textobj#user#plugin` stayed callable after
   * `textobj-user` was turned off. `VsCodeExtensions.TEARDOWN` is where an extension names what the
   * owner does not cover; IntelliJ reaches the same function through `VimExtension.dispose`.
   */
  @Test
  fun `test disabling it unregisters the functions too`() {
    Session("x\n", "textobj-user")
    assertTrue(injector.functionService.getBuiltInFunction("textobj#user#plugin") != null)

    injector.extensionLoader.disableExtension("textobj-user")

    assertTrue(
      injector.functionService.getBuiltInFunction("textobj#user#plugin") == null,
      "the function the extension registered should be gone with it",
    )
  }

  @Test
  fun `test yanking the whole buffer puts it in the register`() {
    val session = Session("one\ntwo\n", "textobj-entire")

    session.type("yae")
    session.type("Gp")

    assertEquals("one\ntwo\none\ntwo\n", session.content)
  }
}
