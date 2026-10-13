/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * ECMAScript's `WeakRef`, which the Kotlin/JS standard library does not wrap, so it is declared here.
 *
 * `deref()` answers `undefined` once the value has been collected, which Kotlin reads as null
 * through a nullable return type.
 */
private external class WeakRef<T : Any>(target: T) {
  fun deref(): T?
}

/** JS has had `WeakRef` since ES2021; Node and every browser this extension can run in have it. */
actual class VimWeakReference<T : Any> actual constructor(referent: T) {
  private val reference = WeakRef(referent)

  actual fun get(): T? = reference.deref()
}
