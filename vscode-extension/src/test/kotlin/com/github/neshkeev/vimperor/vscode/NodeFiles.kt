/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

/**
 * Reading the repository from a test, which is what harvesting IdeaVim's fixtures needs.
 *
 * The Kotlin/JS tests run under Node, so `fs` is there. The working directory is wherever Gradle
 * happens to put the test bundle, so nothing may be resolved relative to it - the root is found by
 * walking up to the file that only the repository root has.
 */

private val nodeFileSystem: dynamic = js("require")("fs")
private val nodePathModule: dynamic = js("require")("path")

internal fun readText(path: String): String = nodeFileSystem.readFileSync(path, "utf8") as String

internal fun fileExists(path: String): Boolean = nodeFileSystem.existsSync(path) as Boolean

internal fun writeTextTo(path: String, content: String) {
    nodeFileSystem.mkdirSync(nodePathModule.dirname(path), js("({recursive: true})"))
    nodeFileSystem.writeFileSync(path, content)
}

/** Every `.kt` file under [directory], depth first. */
internal fun kotlinFilesUnder(directory: String): List<String> {
    val found = mutableListOf<String>()
    fun walk(at: String) {
        val entries = nodeFileSystem.readdirSync(at, js("({withFileTypes: true})"))
        val size = entries.length as Int
        for (index in 0 until size) {
            val entry = entries[index]
            val path = "$at/${entry.name as String}"
            if (entry.isDirectory() as Boolean) walk(path) else if (path.endsWith(".kt")) found += path
        }
    }
    if (fileExists(directory)) walk(directory)
    return found
}

/**
 * The repository root, found by walking up from the working directory.
 *
 * `settings.gradle.kts` is the marker because it exists once and only at the top. Null rather than
 * an exception when it is not found, so that a test can say what it could not do.
 */
internal fun repositoryRoot(): String? {
    var at = js("process").cwd() as String
    while (true) {
        if (fileExists("$at/settings.gradle.kts")) return at
        val parent = nodePathModule.dirname(at) as String
        if (parent == at) return null
        at = parent
    }
}
