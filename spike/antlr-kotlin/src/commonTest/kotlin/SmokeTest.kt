/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
  @Test
  fun `toolchain runs tests on this platform`() {
    assertEquals(4, 2 + 2)
  }
}
