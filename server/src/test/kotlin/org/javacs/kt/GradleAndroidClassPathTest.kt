package org.javacs.kt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Opt-in integration tests: require a compatible Gradle installation and Android SDK. */
class GradleAndroidClassPathTest {
    companion object {
        @JvmStatic @BeforeClass fun requireAndroidTools() {
            assumeTrue("Set KLS_ANDROID_TEST_GRADLE to a compatible Gradle executable",
                !System.getenv("KLS_ANDROID_TEST_GRADLE").isNullOrBlank())
            assumeTrue("Set ANDROID_HOME to an SDK with Android 36 installed",
                !System.getenv("ANDROID_HOME").isNullOrBlank())
        }
    }

    private val agpVersion = System.getenv("KLS_ANDROID_TEST_AGP_VERSION") ?: "9.2.1"

    @Test fun `legacy Android variants retain AAR JVM and project dependencies`() = withWorkspace { workspace, script ->
        val output = runGradle(workspace, script, ":legacy-consumer:kotlinLSPProjectDeps")
        val classpath = emittedClasspath(output)
        assertDependency(classpath, "android.jar")
        assertDependency(classpath, "core-1.18.0-api.jar")
        assertDependency(classpath, "okhttp-4.12.0.jar")
        assertTrue("Expected legacy project classes.jar in $classpath",
            classpath.any { it.contains("legacy-library") && it.endsWith("classes.jar") })
    }

    @Test fun `Android multiplatform resolution selects classes and preserves JVM target`() = withWorkspace { workspace, script ->
        assumeTrue("The new Android KMP fixture requires AGP 9", agpVersion.startsWith("9."))
        val unresolved = runGradle(workspace, script, ":kmp-consumer:resolveUnqualifiedAndroidClasspath", expectSuccess = false)
        assertTrue("Fixture must reproduce ambiguous artifact selection: $unresolved",
            unresolved.contains("artifactType") && unresolved.contains("android-classes-jar") && unresolved.contains("android-lint"))

        val classpath = emittedClasspath(runGradle(workspace, script, ":kmp-consumer:kotlinLSPProjectDeps"))
        assertDependency(classpath, "core-1.18.0-api.jar")
        assertDependency(classpath, "okhttp-4.12.0.jar")
        assertDependency(classpath, "kotlinx-coroutines-core-jvm-1.10.2.jar")
        assertDependency(classpath, "junit-4.13.2.jar")
        assertTrue("Expected KMP project classes.jar in $classpath",
            classpath.any { it.contains("kmp-library") && it.endsWith("classes.jar") })
    }

    @Test fun `plain JVM source sets retain JAR dependencies`() = withWorkspace { workspace, script ->
        val classpath = emittedClasspath(runGradle(workspace, script, ":jvm:kotlinLSPProjectDeps"))
        assertDependency(classpath, "junit-4.13.2.jar")
    }

    @Test fun `Android target dispatch does not depend on target name`() = withWorkspace { workspace, script ->
        assumeTrue("The target-model fixture requires AGP 9", agpVersion.startsWith("9."))
        val classpath = emittedClasspath(runGradle(workspace, script, ":renamed-target:kotlinLSPProjectDeps"))
        assertDependency(classpath, "core-1.18.0-api.jar")
        assertDependency(classpath, "okhttp-4.12.0.jar")
        assertTrue("Android targets must emit class JARs rather than raw AARs: $classpath",
            classpath.none { it.endsWith(".aar") })
    }

    private fun withWorkspace(test: (Path, Path) -> Unit) {
        val workspace = Files.createTempDirectory("kls-android-classpath")
        try {
            testResourcesRoot().resolve("androidClasspathWorkspace").toFile().copyRecursively(workspace.toFile(), overwrite = true)
            val script = workspace.resolve("classpath.gradle")
            javaClass.getResourceAsStream("/projectClassPathFinder.gradle")!!.use {
                Files.copy(it, script)
            }
            test(workspace, script)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun runGradle(workspace: Path, script: Path, task: String, expectSuccess: Boolean = true): String {
        val log = workspace.resolve("gradle-output.log")
        val process = ProcessBuilder(
            System.getenv("KLS_ANDROID_TEST_GRADLE"), "-I", script.toString(), task,
            "-PagpVersion=$agpVersion", "--console=plain", "--no-configuration-cache"
        ).directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly().waitFor()
        }
        val output = Files.readString(log)
        assertTrue("Gradle timed out: $output", finished)
        val exitCode = process.exitValue()
        if (expectSuccess) {
            assertEquals(output, 0, exitCode)
        } else {
            assertTrue("Expected unqualified resolution to fail: $output", exitCode != 0)
        }
        return output
    }

    private fun emittedClasspath(output: String): List<String> = output.lineSequence()
        .filter { it.startsWith("kotlin-lsp-gradle ") }
        .map { it.removePrefix("kotlin-lsp-gradle ") }
        .toList()

    private fun assertDependency(classpath: List<String>, name: String) {
        assertTrue("Expected $name in $classpath", classpath.any { it.endsWith("/$name") })
    }
}
