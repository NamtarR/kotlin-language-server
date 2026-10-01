# Modernize the build and embedded Kotlin compiler

Status: plan approved on October 1, 2026; build-system stage implemented and approved for commit/push after real-project review, embedded compiler migration deferred.

Scope update: after the compiler feasibility gate, the user approved completing the build-system upgrade first and planning the embedded compiler upgrade separately. This stage uses Gradle 9.7.0, `config-plugins`, Java 21, and KGP 2.4.20 while retaining embedded Kotlin/compiler/runtime dependencies at 2.1.0. Java 25 runtime support and its CI lane are explicitly deferred: the retained compiler's bundled IntelliJ Java-version parser rejects Java 25. The original combined acceptance criteria below remain the longer-term compiler migration goals.

Planning baseline: `main` at `1fe7a683e7e6d28c836f6152258e0d7a313b88aa`, including the Android classpath changes.

## Objective

Migrate KLS to a Gradle 9 build using an included `config-plugins` convention-plugin build, and upgrade its embedded compiler and aligned Kotlin libraries to the latest stable Kotlin release while preserving language-server behavior.

### Approved targets

| Component | Target | Reason |
| --- | --- | --- |
| Gradle | 9.7.0 | Within Kotlin 2.4.20's fully supported range |
| Kotlin Gradle plugin and embedded compiler | 2.4.20 | Latest stable release as of October 1, 2026 |
| Build logic | `config-plugins/` included build | Replaces `buildSrc` using existing convention plugins |
| Gradle/build-logic JVM | Java 21 | Common build baseline, satisfying Gradle 9's Java 17+ requirement |
| Server/shared JVM target and minimum server runtime | Java 21 | Approved upgrade from Java 11 |
| Additional server runtime coverage | Java 25 | Verify compatibility on the newer LTS release |

Gradle 9.8.0 is the latest release, but Kotlin's documented fully supported range currently ends at 9.7.0. Pin 9.7.0 rather than adding another compatibility uncertainty. Pin these versions for the implementation instead of using dynamic latest-version selectors.

## Problem

The target project genuinely depends on `org.jetbrains.kotlin:kotlin-stdlib:2.3.21`. Its metadata version is 2.3.0, while KLS's embedded Kotlin 2.1.0 compiler reports that it can read metadata only up to 2.2.0. An example affected class is `kotlin.random.Random`.

Gradle 9.5.1 also contains `kotlin-stdlib-2.3.20.jar`, which has appeared in diagnostics. Filtering that Gradle-internal JAR alone would not solve the project's genuine dependency incompatibility. The revised task also modernizes KLS's own build and Java baseline.

## Acceptance criteria

- [ ] The wrapper uses Gradle 9.7.0, with updated wrapper files and a distribution checksum.
- [ ] `config-plugins` supplies the existing convention plugins; `buildSrc` is removed.
- [ ] Kotlin Gradle plugin, compiler, scripting, reflection, stdlib, and compiler-plugin dependencies resolve consistently to 2.4.20 where applicable.
- [ ] KLS resolves dependencies carrying Kotlin 2.3 and 2.4 metadata without metadata-version errors.
- [ ] File analysis, expression reanalysis, diagnostics, completion, navigation, ordinary scripts, and Gradle Kotlin DSL behavior are preserved.
- [ ] Optional Java-interoperability code generation remains functional.
- [ ] Build, static analysis, packaging, and CI work with the new toolchain.
- [ ] Server/shared target Java 21; the packaged server runs on Java 21 and Java 25, and the minimum runtime is documented.
- [ ] If the compiler upgrade requires a larger redesign, a concrete blocker report identifies that work before expanding scope.

A blocker report completes the feasibility assessment, not the full migration.

## Current implementation

- This checkout identifies itself as version 1.3.14 in `gradle.properties`; the original reported environment used KLS 1.3.13 with the same embedded compiler version.
- Wrapper: Gradle 8.12.
- Kotlin catalog version: 2.1.0.
- `gradle.properties` sets `javaVersion=11`.
- `buildSrc` contains three small precompiled convention plugins: Kotlin/JVM toolchain configuration, distribution archive naming, and Maven publishing configuration.
- The root build currently obtains Kotlin plugin availability implicitly through `buildSrc`; an included build needs explicit plugin resolution.
- The catalog controls Kotlin versions, `platform/build.gradle.kts` constrains dependencies, and `server/build.gradle.kts` declares compiler and scripting dependencies.
- The server embeds the unshaded `kotlin-compiler` and scripting artifacts. Its IntelliJ imports make shading significant.
- `server/src/main/kotlin/org/javacs/kt/compiler/Compiler.kt` relies on `KotlinCoreEnvironment`, `TopDownAnalyzerFacadeForJVM`, `BindingContext`, `LazyTopDownAnalyzer`, expression-typing services, legacy scripting registration, and `GenerationState.Builder`/`KotlinCodegenFacade`.
- `CompilerTest` already exercises file analysis, editing, type resolution, and expression recompilation.
- `shared/src/main/resources/kotlinDSLClassPathFinder.gradle` includes Gradle installation and cached module JARs. The compiler uses a separate environment for build scripts when configured, so source and build-script diagnostics need separate verification.
- CI includes a Java 11 build lane; the deploy workflow runs Gradle on Java 11. Docker defaults to Java 17.

### Compiler feasibility findings

Kotlin 2.4 officially drops supported K1 compilation. However, upstream 2.4.20 still contains `KotlinCoreEnvironment` and `TopDownAnalyzerFacadeForJVM`; classic entry points remain deprecated, including error-level deprecations. This leaves a potential compatibility route, but API presence does not prove correct runtime behavior or support for newer language features.

Upstream 2.4.20 `GenerationState` no longer has the builder used by KLS. Scripting registration has also changed. Consequently, this cannot be planned as a version-only update. Verify the exact unshaded artifacts during the trial; earlier inspection of a shaded 2.3.21 artifact alone is insufficient.

## Proposed approach

Treat this as a staged migration with a compiler feasibility gate. Keep each implementation checkpoint buildable; combine dependency updates and required API adaptations where separating them would break compilation.

### Decisions and invariants

- Interpret `config-plugins` as a local included Gradle build registered through `pluginManagement { includeBuild("config-plugins") }`.
- Preserve existing convention-plugin IDs and distribution/publication behavior.
- Import the root version catalog explicitly into the included build.
- Keep build-logic JVM configuration separate from the server's `javaVersion` setting, even though both approved baselines are Java 21.
- Let Gradle manage the Kotlin version used by `kotlin-dsl`; align the application's KGP and embedded compiler separately.
- Set `javaVersion=21`, use JDK 21 in build/deploy workflows, update Docker's default to 21, and verify the packaged server on Java 25 as well as 21.
- Preserve unshaded compiler integration unless evidence requires a coordinated alternative.
- Do not disable metadata checks, substitute an older project stdlib, or remove optional code generation to make the upgrade compile.
- Preserve the Android classpath baseline and its verification results before replacing a local installation with the upgraded distribution.

### Escalation conditions

Return for a scope decision if:

- Classic analysis cannot reliably analyze the target metadata or required source constructs.
- Scripting or code generation requires substantial FIR/IR/backend work.
- Compiler dependencies require raising the server's minimum runtime beyond the approved Java 21 baseline.
- A third-party build plugin needs replacement rather than a bounded compatibility update.

## Implementation subtasks

### 1. Establish the compiler feasibility gate

**Change:** In an isolated implementation trial, resolve the exact unshaded 2.4.20 artifacts and enumerate compilation/runtime incompatibilities. Check classic analysis, scripting registration, code generation, and dependency JVM requirements.

**Touchpoints:** `gradle/libs.versions.toml`, `server/build.gradle.kts`, `platform/build.gradle.kts`, `server/src/main/kotlin/org/javacs/kt/compiler/Compiler.kt`, and compiler consumers identified by compilation.

**Verification:**

- Run `./gradlew :server:compileKotlin :server:compileTestKotlin`.
- Inspect resolved compiler/scripting/plugin dependencies.
- Exercise file analysis, expression reanalysis, a script, and enabled code generation after minimal adaptations.
- Check the actual artifacts' minimum JVM requirements against Java 21.

**Depends on:** None.

**Done when:** A bounded compatibility route is demonstrated, or a blocker report identifies the larger migration required.

### 2. Replace `buildSrc` with `config-plugins`

**Change:** Move the three convention plugins into an included build, import the catalog, and make root Kotlin plugin resolution explicit. Configure build logic for Java 21 independently of the server target.

**Touchpoints:** `settings.gradle.kts`, new `config-plugins/settings.gradle.kts`, `config-plugins/build.gradle.kts`, the three convention-plugin scripts, root plugin declaration, and `buildSrc`.

**Verification:**

- Run `./gradlew help --warning-mode=all` on JDK 21.
- Compile the included build.
- Confirm server/shared compilation, distribution task names, and generated publication metadata still work.

**Depends on:** Baseline assessment from subtask 1; the structural migration can proceed even if the embedded compiler is blocked.

**Done when:** Plugin resolution works without `buildSrc`, preserving existing behavior.

### 3. Upgrade Kotlin with its required compatibility adaptations

**Change:** Align Kotlin dependencies to 2.4.20 and apply the bounded analysis, scripting, and code-generation adaptations established by the feasibility gate.

**Touchpoints:** Catalog/platform/server dependencies, `Compiler.kt`, affected compiler consumers, and focused regression tests.

**Verification:**

- Run `CompilerTest` and `CompiledFileTest` using `./gradlew :server:test --tests org.javacs.kt.CompilerTest --tests org.javacs.kt.CompiledFileTest`.
- Add explicit metadata regressions for stdlib 2.3.21 and 2.4.20, including `kotlin.random.Random`; verify resolution and diagnostics.
- Run `DiagnosticTest`, representative completion/navigation tests, `SimpleScriptTest`, `ScriptTest`, and `GradleDSLScriptTest`.
- Test enabled code generation and Java consumption of generated classes.
- Inspect runtime dependency resolution for conflicting compiler versions.

**Depends on:** Successful compiler gate and subtask 2.

**Done when:** Kotlin 2.4.20 compiles and passes the targeted behavioral checks.

### 4. Upgrade Gradle and align build infrastructure

**Change:** Upgrade the wrapper to 9.7.0, fix evidenced Gradle API incompatibilities, set `javaVersion=21`, and update build/deployment JVMs and Docker's default to Java 21. Upgrade Detekt, license-reporting, and test-classpath plugins only as required for compatibility. Configure Java 25 runtime coverage while keeping Java 21 as the shipped bytecode target and minimum runtime.

**Touchpoints:** Wrapper files, `gradle.properties`, build scripts/catalog, `.github/workflows/build.yml`, `.github/workflows/deploy.yml`, `Dockerfile`, and `BUILDING.md`.

**Verification:**

- Confirm `./gradlew --version` reports Gradle 9.7.0 running on JDK 21.
- Run `./gradlew help --warning-mode=all`, `./gradlew detekt`, and `./gradlew :server:build :shared:build`.
- Verify the shipped artifacts target Java 21 and run the server on Java 21 and 25. Do not turn the Java 25 coverage lane into Java 25-targeted release artifacts.
- Generate publication metadata and run `./gradlew :server:licenseReport`.
- Confirm tests actually execute under Gradle 9.

**Depends on:** Subtask 3.

**Done when:** Build, analysis, and CI configuration support Gradle 9.7.0 with the approved Java baseline and runtime coverage.

### 5. Verify the packaged server and integrated workflows

**Change:** Complete regression coverage and document verified compatibility and runtime requirements.

**Verification:**

- Run `./gradlew :server:test :shared:test`.
- Run Android classpath integration tests with their required environment (`KLS_ANDROID_TEST_GRADLE` and `ANDROID_HOME`); distinguish executed tests from skips.
- Build `./gradlew :server:installDist :server:distZip :server:distTar`.
- Check executable permissions, archive names, and bundled dependency versions.
- Smoke-test the Java 21-targeted distribution on Java 21 and 25 through LSP, including project sources, Gradle build scripts, editing, and completion; perform the LSP/Zed integration check against the user's project.

**Depends on:** Subtask 4.

**Done when:** The packaged server passes the agreed regression checks.

## Acceptance-criteria coverage

| Requirement | Subtask and verification |
| --- | --- |
| Compiler feasibility and JVM constraints | 1: compilation, artifact inspection, and runtime trial |
| Included convention-plugin build | 2: plugin resolution, compilation, distribution/publication checks |
| Kotlin alignment, metadata, analysis, scripts, code generation | 3: dependency inspection and focused behavioral regressions |
| Gradle wrapper, tooling, CI | 4: wrapper version, builds, Detekt, license/publication checks |
| Java 21 baseline and Java 25 compatibility | 4–5: target inspection and packaged runtime checks |
| Packaging and end-to-end behavior | 5: suites, Android integration, distribution inspection, LSP/Zed smoke tests |

## Risks and edge cases

- Internal compiler APIs are not stable; source compatibility does not guarantee runtime compatibility.
- The main risk is continuing classic analysis against a compiler whose supported frontend is K2. Error suppression alone is insufficient evidence of compatibility.
- Optional code generation defaults to disabled but still must compile and work when enabled.
- Gradle Kotlin DSL support loads Gradle and cached Kotlin libraries, so it needs verification separately from project-source analysis.
- Older build plugins may depend on Gradle APIs removed in version 9.
- KLS resolves JDK classes using the JDK running the server. Verify Java 25 execution as well as compilation with Java 21.

## Out of scope

- A full FIR/Analysis API migration without separately approved planning. If the feasibility gate requires it, return to feature-level planning rather than expanding this task automatically.
- Android artifact-selection changes and broad dependency-cache or Gradle library filtering.
- Unrelated dependency upgrades or build cleanup.

## Open questions

No target-version or Java-baseline decisions remain open. The compiler feasibility gate may produce a scope decision before implementation can continue.

## References

- [Kotlin releases](https://kotlinlang.org/docs/releases.html)
- [Kotlin Gradle plugin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Kotlin 2.4 compatibility](https://kotlinlang.org/docs/compatibility-guide-24.html)
- [Gradle 9 migration](https://docs.gradle.org/9.7.0/userguide/upgrading_major_version_9.html)
- [Gradle 9.7.0 Java compatibility](https://docs.gradle.org/9.7.0/userguide/compatibility.html)
- [Kotlin 2.4.20 classic analysis entry points](https://github.com/JetBrains/kotlin/blob/v2.4.20/compiler/cli/cli-jvm/src/org/jetbrains/kotlin/cli/jvm/compiler/TopDownAnalyzerFacadeForJVM.kt)
- [Kotlin 2.4.20 GenerationState](https://github.com/JetBrains/kotlin/blob/v2.4.20/compiler/backend/src/org/jetbrains/kotlin/codegen/state/GenerationState.kt)
