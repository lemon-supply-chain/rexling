plugins { id("com.android.test") }
android {
    namespace="dev.lemon.rex.benchmark"
    compileSdk {version=release(37)}
    defaultConfig {
        minSdk=28
        targetSdk=37
        testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath=":app"
    experimentalProperties["android.experimental.self-instrumenting"]=true
    buildTypes {
        create("optimized") {isDebuggable=true;signingConfig=signingConfigs.getByName("debug");matchingFallbacks += "release"}
        create("profile") {isDebuggable=true;signingConfig=signingConfigs.getByName("debug");matchingFallbacks += "release"}
    }
    compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
}
kotlin {compilerOptions {jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)}}
androidComponents {beforeVariants(selector().all()) {it.enable=it.buildType in listOf("optimized","profile")}}
dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
