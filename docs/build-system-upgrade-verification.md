# Build-system upgrade verification

Date: October 1, 2026. Branch: `modernize-build-compiler`.

## Implemented scope

- Gradle wrapper 9.7.0, including regenerated scripts/JAR and the official distribution checksum. The wrapper JAR checksum matches the published Gradle 9.7.0 checksum.
- Local convention plugins moved from `buildSrc` into the included `config-plugins` build, preserving plugin IDs, distribution names, and publication configuration.
- Java 21 toolchain, bytecode target, CI/deploy JDK, and Docker default.
- KGP 2.4.20 versioned separately from embedded compiler/runtime Kotlin 2.1.0. Server/shared dependencies explicitly use the existing catalog versions; source language/API settings remain 2.1.
- Detekt upgraded to 1.23.8 for Java 21 support. Five existing baseline IDs migrated to the new rule/signature names; the baseline was not broadly regenerated.
- License plugin upgraded to 0.9.91 to avoid illegal mutation of Gradle 9 configurations.
- Server test Gradle cache isolated from the newer build tooling and seeded with the retained stdlib. The Kotlin DSL fixture now pins its own Gradle 8.12 wrapper. A completion assertion checks the expected parameterized overload without assuming overload ordering.

Java 25 runtime support was explicitly deferred after validation showed that Kotlin 2.1.0's bundled IntelliJ Java-version parser rejects Java 25. No compiler implementation or server runtime API was changed to work around that limitation.

## Successful checks

Local checks used OpenJDK 21.0.2. Maven 3.9.11 was provided on `PATH` for the Maven workspace test.

```sh
./gradlew --version
./gradlew help --warning-mode=all
./gradlew :server:build :shared:build detekt :server:licenseReport \
  :shared:generatePomFileForGprPublication :server:generatePomFileForGprPublication \
  :shared:generateMetadataFileForGprPublication :server:generateMetadataFileForGprPublication \
  :config-plugins:check
```

- Server suite: 161 tests, zero failures/errors, six skipped opt-in Android integration tests; 155 passed. Gradle Kotlin DSL completion/hover, ordinary scripts, compiler analysis, diagnostics, and completion tests executed successfully.
- Shared suite: three tests passed, no skips or failures.
- Detekt and convention-plugin validation passed.
- ZIP/TAR distributions and `installDist` were built. Installed and archived Unix launchers retain executable permissions.
- All class files in the server/shared JARs have major version 65 (Java 21). Publication metadata declares JVM 21.
- The installed distribution contains 12 Kotlin runtime JARs, all version 2.1.0, and no KGP 2.4.20 tooling JARs. Runtime dependency reporting independently confirmed the retained compiler/scripting/plugin versions.
- A stdio LSP smoke test of the installed distribution on Java 21 initialized the server, opened a Kotlin source file, verified hover resolved a function, and shut down cleanly.
- `git diff --check` passed.

## Deferred or external checks

- Java 25 runtime: tested and blocked by the retained compiler; deferred by explicit user decision. Details are in [the compiler feasibility report](kotlin-2.4-compiler-feasibility.md).
- Opt-in Android integration tests: skipped because their required Gradle/Android SDK environment was not configured.
- macOS/Windows CI, Docker image builds, and interactive Zed validation were not run locally. CI configuration now uses Java 21 on all three OS lanes. A real-project stdio LSP follow-up was performed as documented below.
- Gradle 10 deprecation warnings remain in third-party build plugins. These do not prevent the Gradle 9.7.0 build.
- License reporting succeeds but reports the existing missing license metadata for `java-decompiler-engine`.

The user approved committing and pushing the build-system changes after reviewing the real-project results and existing blocker. The separate embedded compiler upgrade remains unimplemented.

## Real-project follow-up: ltaw-app

The user requested validation against `/home/namtarr/Projects/ltaw/ltaw-app` and made approval conditional on KLS building and working there.

The actual `server/build/install/server/bin/kotlin-language-server` was rebuilt with `:server:installDist` and launched on Java 21 against the real project root, using its existing Gradle 9.5.1 wrapper and dependency configuration. The server used a fresh database under `/tmp/opencode` and a 2 GiB heap. Project source/build files were not edited; the completion probe used an LSP in-memory edit that was restored.

### Results

- Distribution build passed.
- LSP initialization completed in 119.78 seconds and returned KLS version 1.3.14.
- Gradle dependency discovery logged successful results for project modules.
- Hover on `Success(block.invoke(data))` in `lib-result/.../DataResult.kt` returned `constructor Success<D, E>(data: D)`.
- Definition lookup on `FsrsCard` in `core-domain/.../FsrsScheduler.kt` navigated to `core-model/src/commonMain/kotlin/com/namtarr/language/model/fsrs/FsrsCard.kt`.
- Document symbols for `DataResult.kt` returned its class/members.
- Hover on the `Random(...)` call in `FsrsMigrationIntervalCalculator.kt` returned `null`, and completion of `nextD` returned zero items.
- Diagnostics reported 22 errors in `FsrsMigrationIntervalCalculator.kt`, 239 in `FsrsScheduler.kt`, and 53 in `DataResult.kt`: 314 errors total. Examples include unresolved `Random`/`Duration` and missing built-in `kotlin.Function1` declarations.
- Shutdown completed with exit code zero. The new run also logged a symbol-index worker exception involving a disposed IntelliJ application; its origin has not been isolated.

### Existing blocker reproduced on the pre-upgrade build

The real-project classpath cache failed with:

```text
org.javacs.kt.classpath.ClassPathCacheEntry.id is not in record set
org.javacs.kt.classpath.BuildScriptClassPathCacheEntry.id is not in record set
```

These failures occur after dependency discovery and prevent the resolved libraries from reaching the analysis environments. The current run therefore does not establish Kotlin 2.3 metadata compatibility; classpath integration fails first.

For comparison, a separate distribution built from `main` at `1fe7a683e7e6d28c836f6152258e0d7a313b88aa`, using the original Gradle 8.12/KGP 2.1.0 build with a Java 21 toolchain, was run against the same real project with a separate fresh database. It initialized in 108.73 seconds and reproduced both cache failures and the same 22/239/53 diagnostic counts. This establishes that the cache failure also exists before the build-system upgrade.

Local artifacts:

- New build: `/tmp/opencode/kls-ltaw-v4iyffy9/report.json`, `protocol.jsonl`, and `stderr.log`.
- Pre-upgrade build: `/tmp/opencode/kls-ltaw-nxvu3g88/report.json`, `protocol.jsonl`, and `stderr.log`.

Conclusion: the build and source-only LSP checks pass, but full real-project operation does not. The initial conditional approval was not satisfied; the user subsequently explicitly approved committing and pushing the build-system upgrade with this existing blocker documented. A separate classpath-cache fix is needed before retesting dependency-backed analysis and addressing the deferred compiler compatibility.
