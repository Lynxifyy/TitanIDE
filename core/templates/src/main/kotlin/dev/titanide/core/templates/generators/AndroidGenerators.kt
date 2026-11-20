package dev.titanide.core.templates.generators

import dev.titanide.core.model.Language
import dev.titanide.core.templates.Gen
import dev.titanide.core.templates.ProjectGenerator
import dev.titanide.core.templates.ScaffoldFile
import dev.titanide.core.templates.ScaffoldRequest
import dev.titanide.core.templates.ScaffoldResult
import dev.titanide.core.templates.TemplateCatalog

/**
 * Android generators. They produce projects that a real Gradle + Android SDK toolchain
 * can assemble; the build engine reports honestly when no such toolchain is present.
 */
class AndroidComposeGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_ANDROID_COMPOSE

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)

        val appBuild = """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
                id("org.jetbrains.kotlin.plugin.compose")
            }

            android {
                namespace = "$pkg"
                compileSdk = ${request.compileSdk}

                defaultConfig {
                    applicationId = "${request.applicationId}"
                    minSdk = ${request.minSdk}
                    targetSdk = ${request.targetSdk}
                    versionCode = 1
                    versionName = "1.0"
                }

                buildTypes {
                    release {
                        isMinifyEnabled = false
                    }
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                buildFeatures {
                    compose = true
                }
            }

            kotlin {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }

            dependencies {
                implementation(platform("androidx.compose:compose-bom:2025.12.01"))
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.material3:material3")
                implementation("androidx.compose.ui:ui-tooling-preview")
                implementation("androidx.activity:activity-compose:1.12.4")
                implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
                implementation("androidx.core:core-ktx:1.17.0")

                testImplementation("junit:junit:4.13.2")
            }
        """.trimIndent() + "\n"

        val mainActivity = """
            package $pkg

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Button
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableIntStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.setValue
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        MaterialTheme {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                HomeScreen()
                            }
                        }
                    }
                }
            }

            @Composable
            fun HomeScreen() {
                var count by remember { mutableIntStateOf(0) }
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "Hello, ${request.projectName}", style = MaterialTheme.typography.headlineSmall)
                    Text(text = "Tapped ${'$'}count times")
                    Button(onClick = { count++ }, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Tap me")
                    }
                }
            }
        """.trimIndent() + "\n"

        val counterTest = """
            package $pkg

            import org.junit.Assert.assertEquals
            import org.junit.Test

            class CounterTest {

                private fun increment(current: Int): Int = current + 1

                @Test
                fun `increment adds one`() {
                    assertEquals(1, increment(0))
                    assertEquals(3, increment(2))
                }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("settings.gradle.kts", Gen.androidSettings(request.projectName)),
                ScaffoldFile("build.gradle.kts", Gen.androidRootBuild()),
                ScaffoldFile("gradle.properties", Gen.gradleProperties()),
                ScaffoldFile("app/build.gradle.kts", appBuild),
                ScaffoldFile("app/src/main/AndroidManifest.xml", Gen.androidManifest(null, "@string/app_name")),
                ScaffoldFile("app/src/main/res/values/strings.xml", Gen.stringsXml(request.projectName)),
                ScaffoldFile("app/src/main/kotlin/$pkgPath/MainActivity.kt", mainActivity),
                ScaffoldFile("app/src/test/kotlin/$pkgPath/CounterTest.kt", counterTest),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        "A Jetpack Compose Android application.",
                        "./gradlew assembleDebug",
                        "./gradlew installDebug",
                    ),
                ),
            ),
            entryPoint = "app/src/main/kotlin/$pkgPath/MainActivity.kt",
            notes = listOf("Assembling an APK needs an Android SDK and JDK; use a remote build agent if the device has none."),
        )
    }
}

class AndroidEmptyGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_ANDROID_EMPTY

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)

        val appBuild = """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "$pkg"
                compileSdk = ${request.compileSdk}

                defaultConfig {
                    applicationId = "${request.applicationId}"
                    minSdk = ${request.minSdk}
                    targetSdk = ${request.targetSdk}
                    versionCode = 1
                    versionName = "1.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            kotlin {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }

            dependencies {
                implementation("androidx.core:core-ktx:1.17.0")
                testImplementation("junit:junit:4.13.2")
            }
        """.trimIndent() + "\n"

        val activity = """
            package $pkg

            import android.os.Bundle
            import android.widget.TextView
            import androidx.activity.ComponentActivity

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(TextView(this).apply {
                        text = "Hello from ${request.projectName}"
                        textSize = 18f
                        setPadding(48, 48, 48, 48)
                    })
                }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("settings.gradle.kts", Gen.androidSettings(request.projectName)),
                ScaffoldFile("build.gradle.kts", Gen.androidRootBuild()),
                ScaffoldFile("gradle.properties", Gen.gradleProperties()),
                ScaffoldFile("app/build.gradle.kts", appBuild),
                ScaffoldFile("app/src/main/AndroidManifest.xml", Gen.androidManifest(null, "@string/app_name")),
                ScaffoldFile("app/src/main/res/values/strings.xml", Gen.stringsXml(request.projectName)),
                ScaffoldFile("app/src/main/kotlin/$pkgPath/MainActivity.kt", activity),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A minimal Android application.", "./gradlew assembleDebug", null),
                ),
            ),
            entryPoint = "app/src/main/kotlin/$pkgPath/MainActivity.kt",
        )
    }
}

class AndroidLibraryGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_ANDROID_LIBRARY

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)
        val lib = Gen.appClassName(request.projectName)

        val settings = """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }

            rootProject.name = "${request.projectName}"
            include(":lib")
        """.trimIndent() + "\n"

        val libBuild = """
            plugins {
                id("com.android.library")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "$pkg"
                compileSdk = ${request.compileSdk}

                defaultConfig {
                    minSdk = ${request.minSdk}
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            kotlin {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }

            dependencies {
                implementation("androidx.core:core-ktx:1.17.0")
                testImplementation("junit:junit:4.13.2")
            }
        """.trimIndent() + "\n"

        val api = """
            package $pkg

            /** Public entry point of the ${request.projectName} library. */
            object $lib {
                fun greet(name: String): String = "Hello, ${'$'}name"
            }
        """.trimIndent() + "\n"

        val apiTest = """
            package $pkg

            import org.junit.Assert.assertEquals
            import org.junit.Test

            class ${lib}Test {
                @Test
                fun `greet includes the name`() {
                    assertEquals("Hello, Titan", $lib.greet("Titan"))
                }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("settings.gradle.kts", settings),
                ScaffoldFile("build.gradle.kts", Gen.androidRootBuild()),
                ScaffoldFile("gradle.properties", Gen.gradleProperties()),
                ScaffoldFile("lib/build.gradle.kts", libBuild),
                ScaffoldFile("lib/src/main/AndroidManifest.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest />\n"),
                ScaffoldFile("lib/src/main/kotlin/$pkgPath/$lib.kt", api),
                ScaffoldFile("lib/src/test/kotlin/$pkgPath/${lib}Test.kt", apiTest),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "An Android library module producing an AAR.", "./gradlew :lib:assembleRelease", null),
                ),
            ),
            entryPoint = "lib/src/main/kotlin/$pkgPath/$lib.kt",
        )
    }
}

/** Kotlin and Java JVM applications share this generator, differing only in source language. */
class JvmGradleGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_GRADLE_JVM

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)
        val app = Gen.appClassName(request.projectName)
        val isJava = request.language == Language.JAVA

        val build = if (isJava) {
            """
                plugins {
                    java
                    application
                }

                application {
                    mainClass.set("$pkg.$app")
                }

                dependencies {
                    testImplementation("junit:junit:4.13.2")
                }

                tasks.test { useJUnit() }
            """.trimIndent() + "\n"
        } else {
            """
                plugins {
                    kotlin("jvm") version "2.2.21"
                    application
                }

                kotlin { jvmToolchain(17) }

                application {
                    mainClass.set("$pkg.${app}Kt")
                }

                dependencies {
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                    testImplementation("junit:junit:4.13.2")
                }

                tasks.test { useJUnit() }
            """.trimIndent() + "\n"
        }

        val mainSource = if (isJava) {
            """
                package $pkg;

                public final class $app {
                    public static void main(String[] args) {
                        System.out.println(greeting("${request.projectName}"));
                    }

                    public static String greeting(String name) {
                        return "Hello from " + name;
                    }
                }
            """.trimIndent() + "\n"
        } else {
            """
                package $pkg

                fun greeting(name: String): String = "Hello from ${'$'}name"

                fun main() {
                    println(greeting("${request.projectName}"))
                }
            """.trimIndent() + "\n"
        }

        val testSource = if (isJava) {
            """
                package $pkg;

                import static org.junit.Assert.assertEquals;
                import org.junit.Test;

                public class ${app}Test {
                    @Test
                    public void greetingIncludesName() {
                        assertEquals("Hello from Titan", $app.greeting("Titan"));
                    }
                }
            """.trimIndent() + "\n"
        } else {
            """
                package $pkg

                import org.junit.Assert.assertEquals
                import org.junit.Test

                class ${app}Test {
                    @Test
                    fun `greeting includes the name`() {
                        assertEquals("Hello from Titan", greeting("Titan"))
                    }
                }
            """.trimIndent() + "\n"
        }

        val sourceDir = if (isJava) "java" else "kotlin"
        val ext = if (isJava) "java" else "kt"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("settings.gradle.kts", "rootProject.name = \"${request.projectName}\"\n"),
                ScaffoldFile("build.gradle.kts", build),
                ScaffoldFile("gradle.properties", "org.gradle.jvmargs=-Xmx1024m\n"),
                ScaffoldFile("src/main/$sourceDir/$pkgPath/$app.$ext", mainSource),
                ScaffoldFile("src/test/$sourceDir/$pkgPath/${app}Test.$ext", testSource),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        "A ${if (isJava) "Java" else "Kotlin"} JVM application.",
                        "./gradlew build",
                        "./gradlew run",
                    ),
                ),
            ),
            entryPoint = "src/main/$sourceDir/$pkgPath/$app.$ext",
        )
    }
}

/** Kotlin-first Android app with a suspend repository, used by the "Kotlin Application" template. */
class AndroidKotlinGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_ANDROID_KOTLIN

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val base = AndroidComposeGenerator().generate(request)
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)

        val repository = ScaffoldFile(
            "app/src/main/kotlin/$pkgPath/Repository.kt",
            """
                package $pkg

                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.withContext

                /** Example suspend repository showing structured concurrency. */
                class Repository {
                    suspend fun loadMessage(): String = withContext(Dispatchers.IO) {
                        "Loaded on ${'$'}{Thread.currentThread().name}"
                    }
                }
            """.trimIndent() + "\n",
        )

        val files = base.files.map { file ->
            if (file.relativePath == "app/build.gradle.kts") {
                file.copy(
                    content = file.content +
                        "\ndependencies {\n    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2\")\n}\n",
                )
            } else {
                file
            }
        } + repository

        return base.copy(files = files)
    }
}

/** Java Android app with a plain Activity and a JUnit test. */
class AndroidJavaGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_ANDROID_JAVA

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)

        val appBuild = """
            plugins {
                id("com.android.application")
            }

            android {
                namespace = "$pkg"
                compileSdk = ${request.compileSdk}

                defaultConfig {
                    applicationId = "${request.applicationId}"
                    minSdk = ${request.minSdk}
                    targetSdk = ${request.targetSdk}
                    versionCode = 1
                    versionName = "1.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            dependencies {
                implementation("androidx.appcompat:appcompat:1.7.1")
                testImplementation("junit:junit:4.13.2")
            }
        """.trimIndent() + "\n"

        val activity = """
            package $pkg;

            import android.os.Bundle;
            import android.widget.TextView;
            import androidx.appcompat.app.AppCompatActivity;

            public class MainActivity extends AppCompatActivity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    TextView view = new TextView(this);
                    view.setText("Hello from ${request.projectName}");
                    view.setTextSize(18f);
                    setContentView(view);
                }
            }
        """.trimIndent() + "\n"

        val test = """
            package $pkg;

            import static org.junit.Assert.assertEquals;
            import org.junit.Test;

            public class CounterTest {
                @Test
                public void incrementAddsOne() {
                    assertEquals(1, increment(0));
                }

                private int increment(int current) {
                    return current + 1;
                }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("settings.gradle.kts", Gen.androidSettings(request.projectName)),
                ScaffoldFile("build.gradle.kts", Gen.androidRootBuild()),
                ScaffoldFile("gradle.properties", Gen.gradleProperties()),
                ScaffoldFile("app/build.gradle.kts", appBuild),
                ScaffoldFile("app/src/main/AndroidManifest.xml", Gen.androidManifest(null, "@string/app_name")),
                ScaffoldFile("app/src/main/res/values/strings.xml", Gen.stringsXml(request.projectName)),
                ScaffoldFile("app/src/main/java/$pkgPath/MainActivity.java", activity),
                ScaffoldFile("app/src/test/java/$pkgPath/CounterTest.java", test),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A Java Android application.", "./gradlew assembleDebug", null),
                ),
            ),
            entryPoint = "app/src/main/java/$pkgPath/MainActivity.java",
        )
    }
}
