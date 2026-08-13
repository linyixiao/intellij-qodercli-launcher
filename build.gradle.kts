plugins {
    id("java")
}

group = "dev.linzhang"
version = "1.3.0"

fun firstExistingDir(vararg paths: String): String? = paths.firstOrNull { file(it).isDirectory }

// Path to a local IntelliJ IDEA installation. The plugin is compiled directly against
// that IDE's own jars and the zip is packaged by hand, so the build always tracks
// whichever IDE version is installed — no IDE download, and no version-pinned cache
// that breaks when the IDE auto-updates.
//
// Resolution order: -PideaHome=<path>  >  IDEA_HOME env var  >  common install locations.
val ideaHome: String = (
    findProperty("ideaHome") as String?
        ?: System.getenv("IDEA_HOME")
        ?: firstExistingDir(
            "/Applications/IntelliJ IDEA.app/Contents",
            "${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app/Contents",
            "/opt/idea",
            "C:/Program Files/JetBrains/IntelliJ IDEA",
        )
    ) ?: error(
    "Cannot locate an IntelliJ IDEA installation. Pass -PideaHome=<path>, set the " +
        "IDEA_HOME environment variable, or install IDEA in a standard location."
)

// Compile with the IDE's bundled JBR: platform classes are Java 25 bytecode, which an
// older javac cannot read. Output is pinned to Java 21 because the oldest IDE this plugin
// supports (2024.2, see since-build in plugin.xml) runs on JBR 21 — so the bytecode stays
// loadable there no matter how new the IDE we happen to compile against is.
val jbrHome: String = firstExistingDir("$ideaHome/jbr/Contents/Home", "$ideaHome/jbr")
    ?: error("No bundled JBR under $ideaHome — does ideaHome point at an IDE installation?")

dependencies {
    compileOnly(fileTree(mapOf("dir" to "$ideaHome/lib", "include" to listOf("*.jar"))))
    compileOnly(fileTree(mapOf("dir" to "$ideaHome/plugins/terminal/lib", "include" to listOf("*.jar"))))
    compileOnly(fileTree(mapOf("dir" to "$ideaHome/plugins/terminal/lib/modules", "include" to listOf("*.jar"))))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.isFork = true
    options.forkOptions.javaHome = file(jbrHome)
}

// Package the plugin the way the IDE expects: <pluginName>/lib/<jar>, zipped.
tasks.register<Zip>("buildPlugin") {
    dependsOn(tasks.named("jar"))
    archiveFileName.set("${project.name}-$version.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("${project.name}/lib") {
        from(tasks.named("jar"))
    }
}
