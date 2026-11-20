package dev.titanide.core.templates.generators

import dev.titanide.core.templates.Gen
import dev.titanide.core.templates.ProjectGenerator
import dev.titanide.core.templates.ScaffoldFile
import dev.titanide.core.templates.ScaffoldRequest
import dev.titanide.core.templates.ScaffoldResult
import dev.titanide.core.templates.TemplateCatalog

class VsCodeExtensionGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_VSCODE

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val name = Gen.sanitizePackage(request.packageName).replace('.', '-').lowercase()

        val packageJson = """
            {
              "name": "$name",
              "displayName": "${request.projectName}",
              "description": "${request.projectName} VS Code extension",
              "version": "0.1.0",
              "publisher": "titanide",
              "engines": { "vscode": "^1.85.0" },
              "categories": ["Other"],
              "activationEvents": ["onCommand:$name.hello"],
              "main": "./out/extension.js",
              "contributes": {
                "commands": [
                  {
                    "command": "$name.hello",
                    "title": "Titan: Hello"
                  }
                ]
              },
              "scripts": {
                "compile": "tsc -p ./",
                "watch": "tsc -watch -p ./"
              },
              "devDependencies": {
                "@types/vscode": "^1.85.0",
                "@types/node": "^20.0.0",
                "typescript": "^5.4.0"
              }
            }
        """.trimIndent() + "\n"

        val tsconfig = """
            {
              "compilerOptions": {
                "module": "commonjs",
                "target": "ES2022",
                "outDir": "out",
                "lib": ["ES2022"],
                "sourceMap": true,
                "rootDir": "src",
                "strict": true
              },
              "exclude": ["node_modules", ".vscode-test"]
            }
        """.trimIndent() + "\n"

        val extension = """
            import * as vscode from "vscode";

            export function activate(context: vscode.ExtensionContext) {
              const disposable = vscode.commands.registerCommand(
                "$name.hello",
                () => {
                  vscode.window.showInformationMessage("Hello from ${request.projectName}");
                }
              );
              context.subscriptions.push(disposable);
            }

            export function deactivate() {}
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("package.json", packageJson),
                ScaffoldFile("tsconfig.json", tsconfig),
                ScaffoldFile("src/extension.ts", extension),
                ScaffoldFile(".vscodeignore", "src/**\nnode_modules/**\n"),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A VS Code extension.", "npm install && npm run compile", "F5 to launch the Extension Development Host"),
                ),
            ),
            entryPoint = "src/extension.ts",
            notes = listOf("Compiling needs Node.js; packaging a VSIX needs @vscode/vsce."),
        )
    }
}

class BrowserExtensionGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_BROWSER

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val manifest = """
            {
              "manifest_version": 3,
              "name": "${request.projectName}",
              "version": "0.1.0",
              "description": "${request.projectName} browser extension",
              "action": { "default_popup": "popup.html" },
              "background": { "service_worker": "background.js" },
              "permissions": ["storage", "activeTab"]
            }
        """.trimIndent() + "\n"

        val background = """
            chrome.runtime.onInstalled.addListener(() => {
              chrome.storage.local.set({ installed: Date.now() });
            });
        """.trimIndent() + "\n"

        val popupHtml = """
            <!DOCTYPE html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <title>${request.projectName}</title>
                <style>
                  body { font-family: system-ui, sans-serif; width: 240px; padding: 12px; }
                  button { width: 100%; padding: 8px; cursor: pointer; }
                </style>
              </head>
              <body>
                <h3>${request.projectName}</h3>
                <button id="ping">Ping background</button>
                <p id="out"></p>
                <script src="popup.js"></script>
              </body>
            </html>
        """.trimIndent() + "\n"

        val popupJs = """
            document.getElementById("ping").addEventListener("click", () => {
              document.getElementById("out").textContent = "pong " + new Date().toLocaleTimeString();
            });
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("manifest.json", manifest),
                ScaffoldFile("background.js", background),
                ScaffoldFile("popup.html", popupHtml),
                ScaffoldFile("popup.js", popupJs),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A Manifest V3 browser extension.", null, "Load the folder as an unpacked extension"),
                ),
            ),
            entryPoint = "popup.js",
        )
    }
}

class IntelliJPluginGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_INTELLIJ

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)
        val pluginId = Gen.sanitizePackage(request.packageName).lowercase()

        val build = """
            plugins {
                id("java")
                id("org.jetbrains.kotlin.jvm") version "2.2.21"
                id("org.jetbrains.intellij.platform") version "2.1.0"
            }

            group = "$pkg"
            version = "0.1.0"

            repositories {
                mavenCentral()
                intellijPlatform { defaultRepositories() }
            }

            dependencies {
                intellijPlatform { create("IC", "2024.2") }
            }

            intellijPlatform {
                pluginConfiguration {
                    id = "$pluginId"
                    name = "${request.projectName}"
                    version = "0.1.0"
                }
            }
        """.trimIndent() + "\n"

        val pluginXml = """
            <idea-plugin>
                <id>$pluginId</id>
                <name>${request.projectName}</name>
                <vendor>TITAN IDE</vendor>
                <depends>com.intellij.modules.platform</depends>
                <actions>
                    <action id="$pluginId.Hello"
                            class="$pkg.HelloAction"
                            text="Titan Hello"
                            description="Shows a greeting">
                        <add-to-group group-id="ToolsMenu" anchor="first" />
                    </action>
                </actions>
            </idea-plugin>
        """.trimIndent() + "\n"

        val action = """
            package $pkg

            import com.intellij.openapi.actionSystem.AnAction
            import com.intellij.openapi.actionSystem.AnActionEvent
            import com.intellij.openapi.ui.Messages

            class HelloAction : AnAction() {
                override fun actionPerformed(event: AnActionEvent) {
                    Messages.showInfoMessage("Hello from ${request.projectName}", "Titan")
                }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("settings.gradle.kts", "rootProject.name = \"${request.projectName.replace(' ', '-')}\"\n"),
                ScaffoldFile("build.gradle.kts", build),
                ScaffoldFile("src/main/resources/META-INF/plugin.xml", pluginXml),
                ScaffoldFile("src/main/kotlin/$pkgPath/HelloAction.kt", action),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "An IntelliJ Platform plugin.", "./gradlew buildPlugin", "./gradlew runIde"),
                ),
            ),
            entryPoint = "src/main/kotlin/$pkgPath/HelloAction.kt",
            notes = listOf("Building needs the Gradle IntelliJ Platform plugin to download the IDE distribution."),
        )
    }
}

class GradlePluginGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_GRADLE_PLUGIN

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)
        val pluginId = Gen.sanitizePackage(request.packageName).lowercase()

        val build = """
            plugins {
                `java-gradle-plugin`
                kotlin("jvm") version "2.2.21"
            }

            repositories { mavenCentral() }

            dependencies {
                testImplementation("junit:junit:4.13.2")
            }

            gradlePlugin {
                plugins {
                    create("${request.projectName.replace(' ', '-').lowercase()}Plugin") {
                        id = "$pluginId"
                        implementationClass = "$pkg.${Gen.appClassName(request.projectName)}Plugin"
                    }
                }
            }
        """.trimIndent() + "\n"

        val pluginClass = """
            package $pkg

            import org.gradle.api.Plugin
            import org.gradle.api.Project

            class ${Gen.appClassName(request.projectName)}Plugin : Plugin<Project> {
                override fun apply(project: Project) {
                    val extension = project.extensions.create("titan", TitanExtension::class.java)
                    project.tasks.register("titanInfo") {
                        it.doLast {
                            println("Titan plugin active: ${'$'}{extension.message.get()}")
                        }
                    }
                }
            }

            open class TitanExtension {
                var message: org.gradle.api.provider.Property<String> =
                    project.objects.property(String::class.java).apply { convention("hello") }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("settings.gradle.kts", "rootProject.name = \"${request.projectName.replace(' ', '-')}\"\n"),
                ScaffoldFile("build.gradle.kts", build),
                ScaffoldFile("src/main/kotlin/$pkgPath/TitanPlugin.kt", pluginClass),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A Gradle plugin.", "./gradlew build", "./gradlew titanInfo"),
                ),
            ),
            entryPoint = "src/main/kotlin/$pkgPath/TitanPlugin.kt",
        )
    }
}

class NpmPackageGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_NPM_PLUGIN

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val name = Gen.sanitizePackage(request.packageName).replace('.', '-').lowercase()

        val packageJson = """
            {
              "name": "$name",
              "version": "0.1.0",
              "description": "${request.projectName}",
              "type": "module",
              "exports": "./src/index.js",
              "files": ["src"],
              "scripts": {
                "test": "node --test"
              }
            }
        """.trimIndent() + "\n"

        val index = """
            /**
             * Returns a greeting for the given name.
             * @param {string} name
             * @returns {string}
             */
            export function greeting(name) {
              return `Hello from ${'$'}{name}`;
            }
        """.trimIndent() + "\n"

        val test = """
            import assert from "node:assert/strict";
            import test from "node:test";
            import { greeting } from "../src/index.js";

            test("greeting includes the name", () => {
              assert.equal(greeting("Titan"), "Hello from Titan");
            });
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("package.json", packageJson),
                ScaffoldFile("src/index.js", index),
                ScaffoldFile("test/index.test.js", test),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A JavaScript npm package.", "npm install", "npm test"),
                ),
            ),
            entryPoint = "src/index.js",
        )
    }
}

class PythonPackageGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_PYTHON_PACKAGE

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val module = Gen.sanitizePackage(request.packageName).replace('.', '_').lowercase()

        val pyproject = """
            [build-system]
            requires = ["setuptools>=68"]
            build-backend = "setuptools.build_meta"

            [project]
            name = "$module"
            version = "0.1.0"
            description = "${request.projectName}"
            requires-python = ">=3.9"

            [tool.pytest.ini_options]
            testpaths = ["tests"]
        """.trimIndent() + "\n"

        val init = """
            \"\"\"${request.projectName} package.\"\"\"


            def greeting(name: str) -> str:
                return f"Hello from {name}"
        """.trimIndent() + "\n"

        val test = """
            from $module import greeting


            def test_greeting() -> None:
                assert greeting("Titan") == "Hello from Titan"
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("pyproject.toml", pyproject),
                ScaffoldFile("$module/__init__.py", init),
                ScaffoldFile("tests/test_package.py", test),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A Python package.", "pip install -e .", "pytest"),
                ),
            ),
            entryPoint = "$module/__init__.py",
        )
    }
}
