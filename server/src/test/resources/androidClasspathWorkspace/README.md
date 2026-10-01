# Android classpath integration fixture

`GradleAndroidClassPathTest` runs the bundled classpath init script against this
fixture in temporary workspaces. The tests are opt-in because they need an Android
SDK and a Gradle installation compatible with the selected AGP version.

```sh
JAVA_HOME=/path/to/jdk-21 \
ANDROID_HOME=/path/to/android-sdk \
KLS_ANDROID_TEST_GRADLE=/path/to/gradle-9.5.1/bin/gradle \
./gradlew :server:test --tests org.javacs.kt.GradleAndroidClassPathTest
```

Install Android SDK platform 36 and the build tools required by AGP. KLS's own
build still uses its configured Java toolchain. If JDK 11 is unavailable locally,
the test command can use `-PjavaVersion=17` with an installed JDK 17.

AGP defaults to 9.2.1. Set `KLS_ANDROID_TEST_AGP_VERSION=8.13.2` to run the
legacy Android and JVM checks against that version; the new Android KMP and
modern-DSL/target-model tests are skipped in that run. Use `:server:cleanTest` before `:server:test` when changing
these environment variables, since they are not Gradle task inputs.

Coverage:

- Legacy Android variant configurations: boot classpath, AAR-derived API JAR,
  ordinary OkHttp JAR, and Android project classes JAR.
- AGP 9 Android KMP configuration: reproduces the unqualified `artifactType`
  failure, then verifies resolution using the patched script.
- Mixed KMP Android/JVM/iOS targets: Android dependencies plus JVM coroutines
  and JUnit dependencies, without looking for native JAR configurations.
- Modern Android application/library DSL: SDK boot classpath and variant compile
  dependencies, enabled with the new DSL for that test invocation.
- Plain JVM source sets: normal JUnit JAR resolution.
- Renamed target dispatch: AGP's new KMP plugin fixes its target name to
  `android`, so this case uses a minimal target-model test double named `mobile`
  over a real Android compile configuration. It checks dispatch by platform type
  rather than name; it does not claim AGP permits renaming its KMP target.
- Clean-versus-built project dependencies: discovery emits missing JAR paths
  without compiling the producer; explicit builds produce those JARs. KLS
  resolves method calls and navigates to Java and Kotlin project sources both
  before and after the dependency JARs exist.

The fixture enables the legacy Android DSL explicitly to exercise the existing
legacy variant branch. The new Android KMP plugin and modern Android DSL are
exercised separately. Source-resolution checks use KLS's runtime stdlib to isolate
the project-JAR/source behavior from dependency metadata compatibility.
