/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

/**
 * The places the caret has been, one per visit.
 *
 * `<C-o>` is mapped to `<Action>(Back)`, and that action is VS Code's Go Back. Go Back keeps a
 * place only when it is at least ten lines from the one before it, and a place this host wrote
 * itself — a paste, a reveal — is not a place at all. Two clicks four lines apart are one stop,
 * so the second `<C-o>` does nothing. This list is every place the caret was actually moved to.
 */
internal class CaretHistory {
  data class Place(val identity: String, val line: Int, val character: Int)

  private val places = ArrayDeque<Place>()
  private var index = -1

  /** Set while [back] or [forward] is putting a stored place on screen, so that move is not stored again. */
  var applying = false

  fun record(place: Place) {
    if (applying) return
    if (places.getOrNull(index) == place) return
    while (places.size > index + 1) places.removeLast()
    places.addLast(place)
    if (places.size > LIMIT) {
      places.removeFirst()
    }
    index = places.lastIndex
  }

  fun back(): Place? {
    if (index <= 0) return null
    index -= 1
    return places[index]
  }

  fun forward(): Place? {
    if (index < 0 || index >= places.lastIndex) return null
    index += 1
    return places[index]
  }

  private companion object {
    const val LIMIT = 100
  }
}
