import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

abstract class DexResidentHelper : DefaultTask() {
    @get:Classpath
    abstract val d8: RegularFileProperty

    @get:Classpath
    abstract val platformClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val helperJar: RegularFileProperty

    @get:Input
    abstract val assetName: Property<String>

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun dex() {
        val output = assets.get().asFile.apply { mkdirs() }
        val scratch = temporaryDir
        execOperations.javaexec {
            classpath(d8)
            mainClass.set("com.android.tools.r8.D8")
            args("--min-api", "31", "--output", scratch.absolutePath)
            platformClasspath.files.forEach { args("--lib", it.absolutePath) }
            args(helperJar.get().asFile.absolutePath)
        }.assertNormalExitValue()
        scratch.resolve("classes.dex").copyTo(output.resolve(assetName.get()), overwrite = true)
    }
}

android {
    namespace = "ai.closepaw.taskhelper"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 31
    }
}

kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

val sdk = androidComponents.sdkComponents
// Deferred: reading bootClasspath eagerly at script evaluation queries the
// Java plugin's targetCompatibility before AGP finalizes it ("targetCompatibility
// is not yet finalized"). Realize it only when a task consumes the provider.
val bootClasspath = providers.provider { sdk.bootClasspath.get() }
val d8Jar = sdk.sdkDirectory.map { it.file("build-tools/35.0.0/lib/d8.jar") }

// Compile only the standalone entry point and shared wire classes into the shell dex.
val compileResidentHelper by tasks.registering(JavaCompile::class) {
    source(fileTree("src/helper/java"), fileTree("src/main/java"))
    classpath = files(bootClasspath)
    destinationDirectory.set(layout.buildDirectory.dir("resident/classes"))
    options.release.set(8)
}

val residentHelperJar by tasks.registering(Jar::class) {
    from(compileResidentHelper.flatMap { it.destinationDirectory })
    archiveFileName.set("resident-helper.jar")
    destinationDirectory.set(layout.buildDirectory.dir("resident"))
}

androidComponents.onVariants { variant ->
    val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
    val dexHelper = tasks.register<DexResidentHelper>("dex${variantName}ResidentHelper") {
        assetName.set("closepaw-task-helper-v1.dex")
        d8.set(d8Jar)
        platformClasspath.from(bootClasspath)
        helperJar.set(residentHelperJar.flatMap { it.archiveFile })
    }
    variant.sources.assets?.addGeneratedSourceDirectory(dexHelper, DexResidentHelper::assets)
}
