plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.test") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.ncorti.ktfmt.gradle") version "0.28.0" apply false
}

// This root project applies no Android plugin, so an unprefixed task name sent by an IDE or
// tool (`gradle assembleDebug` from the repo root) matches nothing here. Mirror the app tasks
// such tooling asks for onto the root project. Variants follow app/build.gradle.kts buildTypes.
val appVariants = listOf("Debug", "Release", "Optimized", "Profile")

(appVariants.flatMap { variant -> listOf("assemble$variant", "install$variant", "lint$variant") } +
    listOf("assembleDebugAndroidTest", "assembleDebugUnitTest", "testDebugUnitTest", "connectedDebugAndroidTest", "lint"))
    .forEach { name ->
        tasks.register(name) {
            group = "aggregation"
            description = "Runs :app:$name."
            dependsOn(":app:$name")
        }
    }

// ktfmt runs per module (see app/build.gradle.kts); mirror it here so
// `./gradlew ktfmtCheck` from the Android project checks every module.
listOf("ktfmtCheck", "ktfmtFormat").forEach { name ->
    tasks.register(name) {
        group = "verification"
        description = "Runs the $name task in every module."
        dependsOn(":app:$name", ":benchmark:$name")
    }
}
