/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.intellij.vim.processors

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.intellij.vim.annotations.ExCommand

class ExCommandProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
  private val visitor = EXCommandVisitor()
  private val commandToClass = mutableMapOf<String, ExCommandBean>()
  private val fileWriter = JsonFileWriter(environment)

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val exCommandsFile = environment.options["ex_commands_file"] ?: return emptyList()

    resolver.getAllFiles().forEach { it.accept(visitor, Unit) }

    val sortedCommandToClass =
      commandToClass.toList().sortedWith(compareBy({ it.first }, { it.second.`class` })).toMap()
    fileWriter.write(exCommandsFile, sortedCommandToClass)

    return emptyList()
  }

  /**
   * Whether the class declares a `(Range, CommandModifier, String)` primary constructor.
   *
   * The primary one is the right one to look at: `ExCommandConstructorInvariantsTest` establishes
   * that when a matching three-argument constructor exists, it is always the primary constructor.
   */
  private fun hasStandardConstructor(classDeclaration: KSClassDeclaration): Boolean {
    val parameters = classDeclaration.primaryConstructor?.parameters ?: return false
    if (parameters.size != 3) return false
    val types = parameters.map { it.type.resolve().declaration.qualifiedName?.asString() }
    return types == listOf(
      "com.maddyhome.idea.vim.ex.ranges.Range",
      "com.maddyhome.idea.vim.vimscript.model.commands.CommandModifier",
      "kotlin.String",
    )
  }

  private inner class EXCommandVisitor : KSVisitorVoid() {
    @OptIn(KspExperimental::class)
    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
      val exCommandAnnotation = classDeclaration.getAnnotationsByType(ExCommand::class).firstOrNull() ?: return
      val commands = exCommandAnnotation.command.split(",")
      val bean = ExCommandBean(
        classDeclaration.qualifiedName!!.asString(),
        hasStandardConstructor(classDeclaration),
      )
      for (command in commands) {
        commandToClass[command] = bean
      }
    }

    override fun visitFile(file: KSFile, data: Unit) {
      file.declarations.forEach { it.accept(this, Unit) }
    }
  }
}