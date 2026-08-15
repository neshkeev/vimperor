import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask

plugins {
  kotlin("multiplatform") version "2.3.20"
  id("com.strumenta.antlr-kotlin") version "1.0.13"
}

repositories {
  mavenCentral()
}

val generateKotlinGrammarSource = tasks.register<AntlrKotlinTask>("generateKotlinGrammarSource") {
  dependsOn("cleanGenerateKotlinGrammarSource")

  source = fileTree(layout.projectDirectory.dir("antlr")) {
    include("**/*.g4")
  }

  // Must match the main build so ported sources need no package edits
  packageName = "com.maddyhome.idea.vim.parser.generated"
  arguments = listOf("-visitor")

  val outDir = "generatedAntlr/${packageName!!.replace(".", "/")}"
  outputDirectory = layout.buildDirectory.dir(outDir).get().asFile
}

kotlin {
  jvmToolchain(21)

  jvm()
  js {
    nodejs()
  }

  sourceSets {
    commonMain {
      kotlin {
        srcDir(generateKotlinGrammarSource)
      }
      dependencies {
        implementation("com.strumenta:antlr-kotlin-runtime:1.0.13")
      }
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
    jvmTest.dependencies {
      implementation(kotlin("test-junit5"))
    }
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}
