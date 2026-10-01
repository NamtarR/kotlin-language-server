# Kotlin 2.4.20 compiler feasibility gate

Date: October 1, 2026.

Branch: `modernize-build-compiler`, based on `main` at `1fe7a683e7e6d28c836f6152258e0d7a313b88aa`.

## Result

The aligned dependency update resolves successfully, and `shared` compiles on JDK 21. The server does not compile with Kotlin 2.4.20. Preserving scripting and optional code generation requires work beyond deprecation opt-ins or a version-only upgrade. This reaches the approved plan's scope-review gate; a bounded full-functionality upgrade has not been demonstrated.

The temporary catalog change from 2.1.0 to 2.4.20 was reverted after investigation. No production compiler behavior was changed, and no feature was removed or disabled.

## Trial

Only `kotlinVersion` in `gradle/libs.versions.toml` was changed for the trial. The existing Gradle 8.12 wrapper and `buildSrc` were retained to isolate compiler compatibility. Commands used the installed OpenJDK 21.0.2 and `-PjavaVersion=21`:

```sh
JAVA_HOME=/home/namtarr/.sdkman/candidates/java/21.0.2-open ./gradlew :server:compileKotlin :server:compileTestKotlin -PjavaVersion=21 --console=plain
JAVA_HOME=/home/namtarr/.sdkman/candidates/java/21.0.2-open ./gradlew :server:dependencies --configuration runtimeClasspath -PjavaVersion=21 --console=plain
```

- `buildSrc` and `shared:compileKotlin` succeeded.
- `server:compileKotlin` failed; test compilation and runtime regressions could not run against the upgraded compiler.
- Runtime dependency reporting succeeded. Compiler, stdlib/JDK adapters, reflection, script runtime, scripting compiler/implementation/common/JVM/unshaded host, SAM-with-receiver plugin, and build-tools API selected 2.4.20. Older transitive Kotlin requests were upgraded through the existing constraints.
- KGP emitted a Gradle 8.12 deprecation warning, not a build-configuration failure. The failure occurred in server source compilation.

After restoring Kotlin 2.1.0, the following baseline check succeeded on JDK 21:

```sh
JAVA_HOME=/home/namtarr/.sdkman/candidates/java/21.0.2-open ./gradlew :server:compileKotlin :server:compileTestKotlin :server:test --tests org.javacs.kt.CompilerTest -PjavaVersion=21 --console=plain
```

Both server compilation tasks passed, and all four `CompilerTest` tests passed with no skips or failures. This validates the restored baseline, not the Kotlin 2.4.20 upgrade.

## Concrete incompatibilities

### 1. Classic-analysis opt-in and error-level deprecations

K1 API diagnostics occur across `CompiledFile.kt`, completion, diagnostics, navigation, hover, quick fixes, references, and other compiler consumers. `Compiler.kt` also needs opt-ins for core-environment and compiler-configuration internals and uses error-level deprecated entry points.

These are potentially mechanical adaptations, but suppressing or opting into them does not establish metadata, newer-source-language, or runtime compatibility.

### 2. Scripting and SAM-with-receiver integration

Compilation reports missing:

- `ComponentRegistrar`
- `ScriptingCompilerConfigurationComponentRegistrar`
- `KotlinScriptDefinition`
- `KotlinScriptDefinitionFromAnnotatedTemplate`
- `ScriptDefinition.FromLegacy` and `asLegacyOrNull`
- `CliSamWithReceiverComponentContributor`

These are used for registering scripting, loading Gradle Kotlin DSL templates, overriding script matching/dependency resolution, and adding SAM-with-receiver behavior to the classic analysis container.

Inspection of the resolved unshaded scripting compiler JAR found `ScriptingK2CompilerPluginRegistrar`, FIR scripting/configuration/resolve extensions, and the FIR compilation pipeline. The existing registrar class is absent. Upstream 2.4.20 SAM-with-receiver registration uses `FirExtensionRegistrar.registerExtension(FirSamWithReceiverExtensionRegistrar(...))`; that is not a direct replacement for KLS's `StorageComponentContainerContributor` registration.

Required larger work: determine and implement a compatible script-analysis path, including script templates, configuration/default imports, Gradle settings/build-script matching, dependency resolution, and SAM-with-receiver semantics. A K2/FIR or Analysis API integration, or a separately approved compatibility approach, needs technical planning and runtime evidence.

### 3. Optional Java-interoperability code generation

`Compiler.generateCode()` depends on `GenerationState.Builder` and `KotlinCodegenFacade.compileCorrectFiles`. Both symbols are missing from the exact unshaded `kotlin-compiler:2.4.20` JAR, confirmed with `javap`.

`GenerationState` still exists, but its constructor does not accept the old `BindingContext`/file inputs. Constructing it alone does not restore compilation. Required larger work: select and integrate a replacement compilation/backend path that produces the expected class files from the current source snapshot, preserving classpath, output cleanup, error handling, and Java interoperability. Disabling code generation is not authorized by the approved plan.

### 4. Smaller API adjustments

- Message collector configuration moved from `CLIConfigurationKeys` to `CommonConfigurationKeys`, with an additional access opt-in.
- Diagnostic `Severity` has new `FIXED_WARNING` and `STRONG_WARNING` values; `ConvertDiagnostic.kt` needs an explicit mapping.

These do not resolve the scripting or backend blockers.

## Verification limits

The failed server compilation prevents testing metadata compatibility, completion, diagnostics, script behavior, and enabled code generation on 2.4.20. Shared/build-logic compilation on JDK 21 does not prove every compiler/runtime dependency is compatible. Java 25 is not installed locally and has not been tested.

The Gradle 9 wrapper, `config-plugins` migration, Java 21 default, and CI changes have not been started; the first subtask requires review before proceeding.

## Decision needed

Choose whether to:

1. Proceed with the independently feasible Gradle 9, `config-plugins`, and Java 21 build modernization while separating and replanning the embedded compiler migration. This requires deciding how to version the build KGP separately from the retained embedded compiler/runtime dependencies and verifying that combination.
2. Expand into a separately planned K2/FIR or Analysis API migration to retain all features with Kotlin 2.4.20.

An older compiler target or reduced scripting/code-generation support would change the approved acceptance criteria and also requires explicit approval. No fallback version has been trialed.

### Approved follow-up scope

The user selected build-system modernization first, with the embedded compiler migration planned separately. KGP is versioned independently from the retained compiler/runtime libraries.

Java 25 runtime verification subsequently failed during compiler initialization with `IllegalArgumentException: 25.0.4.1` in `com.intellij.util.lang.JavaVersion.parse`. Direct inspection of the exact Kotlin 2.1.0 runtime class also showed that `tryParse("25")` and `tryParse("25.0.1")` return `null`, while `tryParse("21")` succeeds. This is a retained compiler-runtime incompatibility, not a Gradle 9 toolchain failure. The user explicitly approved deferring Java 25 runtime support and its CI lane to the compiler upgrade and finishing the build stage on Java 21.

## Evidence references

- [Approved migration plan](kotlin-2.3-compiler-update-plan.md)
- [Kotlin 2.4 compatibility guide](https://kotlinlang.org/docs/compatibility-guide-24.html)
- [Kotlin 2.4.20 SAM-with-receiver registration](https://github.com/JetBrains/kotlin/blob/v2.4.20/plugins/sam-with-receiver/sam-with-receiver.cli/src/org/jetbrains/kotlin/samWithReceiver/SamWithReceiverPlugin.kt)
- [Kotlin 2.4.20 GenerationState](https://github.com/JetBrains/kotlin/blob/v2.4.20/compiler/backend/src/org/jetbrains/kotlin/codegen/state/GenerationState.kt)
