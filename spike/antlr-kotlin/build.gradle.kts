plugins {
  kotlin("multiplatform") version "2.3.20"
}

repositories {
  mavenCentral()
}

kotlin {
  jvmToolchain(21)

  jvm()
  js {
    nodejs()
  }

  sourceSets {
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
