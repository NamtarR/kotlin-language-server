package org.javacs.kt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import org.javacs.kt.compiler.Compiler
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils

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
        val projectJar = classpath.single { it.contains("kmp-library") && it.endsWith("classes.jar") }
        assertFalse("Discovery should not build the KMP dependency JAR", Files.exists(Paths.get(projectJar)))
        assertKotlinSourceSymbolResolves(workspace, Paths.get(projectJar))
        runGradle(workspace, script, ":kmp-library:bundleAndroidMainClassesToCompileJar")
        assertTrue("Expected the explicitly built KMP project JAR", Files.isRegularFile(Paths.get(projectJar)))
        assertKotlinSourceSymbolResolves(workspace, Paths.get(projectJar))
    }

    @Test fun `plain JVM source sets retain JAR dependencies`() = withWorkspace { workspace, script ->
        val classpath = emittedClasspath(runGradle(workspace, script, ":jvm:kotlinLSPProjectDeps"))
        assertDependency(classpath, "junit-4.13.2.jar")
    }

    @Test fun `modern Android DSL resolves boot AAR JVM and project classpaths`() = withWorkspace { workspace, script ->
        assumeTrue("The new Android DSL fixture requires AGP 9", agpVersion.startsWith("9."))
        val classpath = emittedClasspath(runGradle(workspace, script, ":modern-consumer:kotlinLSPProjectDeps",
            extraArguments = listOf("-Pandroid.newDsl=true", "-Pandroid.builtInKotlin=true")))
        assertDependency(classpath, "android.jar")
        assertDependency(classpath, "core-1.18.0-api.jar")
        assertDependency(classpath, "okhttp-4.12.0.jar")
        assertTrue("Expected modern project classes.jar in $classpath",
            classpath.any { it.contains("modern-library") && it.endsWith("classes.jar") })
    }

    @Test fun `Android target dispatch does not depend on target name`() = withWorkspace { workspace, script ->
        assumeTrue("The target-model fixture requires AGP 9", agpVersion.startsWith("9."))
        val classpath = emittedClasspath(runGradle(workspace, script, ":renamed-target:kotlinLSPProjectDeps"))
        assertDependency(classpath, "core-1.18.0-api.jar")
        assertDependency(classpath, "okhttp-4.12.0.jar")
        assertTrue("Android targets must emit class JARs rather than raw AARs: $classpath",
            classpath.none { it.endsWith(".aar") })
    }

    @Test fun `project sources resolve with missing and built dependency JARs`() = withWorkspace { workspace, script ->
        val output = runGradle(workspace, script, ":legacy-consumer:kotlinLSPProjectDeps")
        val classpath = emittedClasspath(output)
        val projectJar = Paths.get(classpath.first {
            it.contains("legacy-library") && it.contains("/debug/") && it.endsWith("classes.jar")
        })
        assertFalse("Discovery must not compile the dependency", output.contains("> Task :legacy-library:"))
        assertFalse("Expected an unbuilt project JAR", Files.exists(projectJar))
        assertSourceSymbolResolves(workspace, projectJar)

        runGradle(workspace, script, ":legacy-library:bundleLibCompileToJarDebug")
        assertTrue("Expected the explicitly built project JAR", Files.isRegularFile(projectJar))
        JarFile(projectJar.toFile()).use {
            assertTrue(it.getEntry("org/javacs/kt/fixture/library/LibraryValue.class") != null)
        }
        val builtClasspath = emittedClasspath(runGradle(workspace, script, ":legacy-consumer:kotlinLSPProjectDeps"))
        assertEquals("Building a dependency should not change discovery output", classpath.toSet(), builtClasspath.toSet())
        assertSourceSymbolResolves(workspace, projectJar)
    }

    private fun assertSourceSymbolResolves(workspace: Path, projectJar: Path) {
        val javaSource = workspace.resolve("legacy-library/src/main/java/org/javacs/kt/fixture/library/LibraryValue.java")
        val stdlib = Paths.get(Unit::class.java.protectionDomain.codeSource.location.toURI())
        Compiler(setOf(javaSource), setOf(projectJar, stdlib), scriptsConfig = ScriptsConfiguration(enabled = false),
            codegenConfig = CodegenConfiguration(), outputDirectory = workspace.toFile()).use { compiler ->
            val consumer = compiler.createKtFile(
                "fun fromLibrary(value: org.javacs.kt.fixture.library.LibraryValue) = value.marker()",
                workspace.resolve("Consumer.kt")
            )
            val (context, _) = compiler.compileKtFile(consumer, listOf(consumer))
            val call = consumer.collectDescendantsOfType<KtCallExpression>().single()
            assertEquals("Int", context.getType(call).toString())
            val target = context[BindingContext.REFERENCE_TARGET, call.calleeExpression as KtReferenceExpression]
            assertTrue("Expected the source method to resolve", target != null)
            val declaration = DescriptorToSourceUtils.descriptorToDeclaration(target!!)
            assertEquals("Navigation should use project sources with or without the JAR",
                javaSource.toString(), declaration?.containingFile?.virtualFile?.path)
        }
    }

    private fun assertKotlinSourceSymbolResolves(workspace: Path, projectJar: Path) {
        val sourcePath = workspace.resolve("kmp-library/src/androidMain/kotlin/org/javacs/kt/fixture/kmp/library/KmpValue.kt")
        val stdlib = Paths.get(Unit::class.java.protectionDomain.codeSource.location.toURI())
        Compiler(emptySet(), setOf(projectJar, stdlib), scriptsConfig = ScriptsConfiguration(enabled = false),
            codegenConfig = CodegenConfiguration(), outputDirectory = workspace.toFile()).use { compiler ->
            val library = compiler.createKtFile(Files.readString(sourcePath), sourcePath)
            val consumer = compiler.createKtFile(
                "fun fromLibrary(value: org.javacs.kt.fixture.kmp.library.KmpValue) = value.marker()",
                workspace.resolve("Consumer.kt")
            )
            val (context, _) = compiler.compileKtFile(consumer, listOf(library, consumer))
            val call = consumer.collectDescendantsOfType<KtCallExpression>().single()
            assertEquals("Int", context.getType(call).toString())
            val target = context[BindingContext.REFERENCE_TARGET, call.calleeExpression as KtReferenceExpression]
            assertTrue("Expected the Kotlin source method to resolve", target != null)
            val declaration = DescriptorToSourceUtils.descriptorToDeclaration(target!!)
            assertEquals("Navigation should use Kotlin project sources with or without the JAR",
                sourcePath, declaration?.containingFile?.virtualFile?.path?.let { Paths.get(it).normalize() })
        }
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

    private fun runGradle(workspace: Path, script: Path, task: String, expectSuccess: Boolean = true,
        extraArguments: List<String> = emptyList()): String {
        val log = workspace.resolve("gradle-output.log")
        val process = ProcessBuilder(listOf(
            System.getenv("KLS_ANDROID_TEST_GRADLE"), "-I", script.toString(), task,
            "-PagpVersion=$agpVersion", "--console=plain", "--no-configuration-cache"
        ) + extraArguments).directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
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
