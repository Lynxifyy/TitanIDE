package dev.titanide.core.templates.generators

import dev.titanide.core.templates.Gen
import dev.titanide.core.templates.ProjectGenerator
import dev.titanide.core.templates.ScaffoldFile
import dev.titanide.core.templates.ScaffoldRequest
import dev.titanide.core.templates.ScaffoldResult
import dev.titanide.core.templates.TemplateCatalog

/**
 * Game-mod generators. Each one states what the produced artifact can and cannot do,
 * because a mobile-built binary is not interchangeable with a desktop one.
 */
class FabricModGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_MINECRAFT_FABRIC

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)
        val modId = pkg.substringAfterLast('.').lowercase().filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "titanmod" }

        val build = """
            plugins {
                id("fabric-loom") version "1.7-SNAPSHOT"
                id("org.jetbrains.kotlin.jvm") version "2.2.21"
            }

            version = "1.0.0"
            group = "$pkg"

            repositories { mavenCentral() }

            dependencies {
                minecraft("com.mojang:minecraft:1.21.1")
                mappings("net.fabricmc:yarn:1.21.1+build.3:v2")
                modImplementation("net.fabricmc:fabric-loader:0.16.0")
                modImplementation("net.fabricmc.fabric-api:fabric-api:0.102.0+1.21.1")
            }

            java {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }

            tasks.processResources {
                inputs.property("version", project.version)
                filesMatching("fabric.mod.json") {
                    expand("version" to project.version)
                }
            }
        """.trimIndent() + "\n"

        val modJson = """
            {
              "schemaVersion": 1,
              "id": "$modId",
              "version": "${'$'}{version}",
              "name": "${request.projectName}",
              "description": "${request.projectName} - a Fabric mod.",
              "authors": ["TITAN IDE"],
              "license": "MIT",
              "environment": "*",
              "entrypoints": {
                "main": ["$pkg.${Gen.appClassName(request.projectName)}Mod"]
              },
              "depends": {
                "fabricloader": ">=0.16.0",
                "minecraft": "~1.21.1",
                "java": ">=21",
                "fabric-api": "*"
              }
            }
        """.trimIndent() + "\n"

        val modClass = """
            package $pkg

            import net.fabricmc.api.ModInitializer
            import org.slf4j.Logger
            import org.slf4j.LoggerFactory

            class ${Gen.appClassName(request.projectName)}Mod : ModInitializer {
                private val logger: Logger = LoggerFactory.getLogger("$modId")

                override fun onInitialize() {
                    logger.info("${request.projectName} initialised")
                }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("settings.gradle.kts", "rootProject.name = \"${request.projectName.replace(' ', '-')}\"\n"),
                ScaffoldFile("build.gradle.kts", build),
                ScaffoldFile("gradle.properties", "org.gradle.jvmargs=-Xmx2048m\n"),
                ScaffoldFile("src/main/resources/fabric.mod.json", modJson),
                ScaffoldFile("src/main/kotlin/$pkgPath/${Gen.appClassName(request.projectName)}Mod.kt", modClass),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        """
                        A Fabric mod for Minecraft Java Edition 1.21.1.

                        ## Compatibility

                        The produced .jar runs on Minecraft Java Edition with Fabric Loader 0.16+.
                        Building requires JDK 21 and network access to the Fabric/Mojang repositories.
                        A jar built here cannot be loaded by the Bedrock edition or by Forge.
                        """.trimIndent(),
                        "./gradlew build",
                        "Place the jar from build/libs into .minecraft/mods",
                    ),
                ),
            ),
            entryPoint = "src/main/kotlin/$pkgPath/${Gen.appClassName(request.projectName)}Mod.kt",
            notes = listOf("Requires JDK 21 and access to the Fabric Maven repository at build time."),
        )
    }
}

class ForgeModGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_MINECRAFT_FORGE

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)
        val modId = pkg.substringAfterLast('.').lowercase().filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "titanmod" }

        val build = """
            plugins {
                id("net.minecraftforge.gradle") version "[6.0,6.2)"
                id("org.jetbrains.kotlin.jvm") version "2.2.21"
            }

            version = "1.0.0"
            group = "$pkg"

            minecraft {
                mappings("official", "1.20.1")
            }

            repositories {
                mavenCentral()
                maven { url = uri("https://maven.minecraftforge.net") }
            }

            dependencies {
                minecraft("net.minecraftforge:forge:1.20.1-47.2.0")
            }

            java {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        """.trimIndent() + "\n"

        val modsToml = """
            modLoader = "javafml"
            loaderVersion = "[47,)"
            license = "MIT"

            [[mods]]
            modId = "$modId"
            version = "1.0.0"
            displayName = "${request.projectName}"
            description = '''${request.projectName} - a Forge mod.'''
        """.trimIndent() + "\n"

        val modClass = """
            package $pkg

            import net.minecraftforge.fml.common.Mod

            @Mod("$modId")
            class ${Gen.appClassName(request.projectName)}Mod
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("settings.gradle.kts", "rootProject.name = \"${request.projectName.replace(' ', '-')}\"\n"),
                ScaffoldFile("build.gradle.kts", build),
                ScaffoldFile("src/main/resources/META-INF/mods.toml", modsToml),
                ScaffoldFile("src/main/kotlin/$pkgPath/${Gen.appClassName(request.projectName)}Mod.kt", modClass),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        """
                        A Forge mod for Minecraft Java Edition 1.20.1.

                        ## Compatibility

                        The produced .jar runs on Minecraft Java Edition with Forge 47+. It does not work on Fabric or Bedrock.
                        ForgeGradle downloads a large toolchain on first build.
                        """.trimIndent(),
                        "./gradlew build",
                        null,
                    ),
                ),
            ),
            entryPoint = "src/main/kotlin/$pkgPath/${Gen.appClassName(request.projectName)}Mod.kt",
            notes = listOf("ForgeGradle downloads a large toolchain; expect a long first build."),
        )
    }
}

class PaperPluginGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_MINECRAFT_PAPER

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)
        val pkgPath = Gen.pathOf(pkg)
        val pluginName = Gen.appClassName(request.projectName)

        val build = """
            plugins {
                java
            }

            group = "$pkg"
            version = "1.0.0"

            repositories {
                mavenCentral()
                maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
            }

            dependencies {
                compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
            }

            java {
                toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            }

            tasks.processResources {
                filesMatching("plugin.yml") {
                    expand("version" to project.version)
                }
            }
        """.trimIndent() + "\n"

        val pluginYml = """
            name: $pluginName
            version: '${'$'}{version}'
            main: $pkg.${pluginName}Plugin
            api-version: '1.21'
            author: TITAN IDE
            description: ${request.projectName}
            commands:
              hello:
                description: Sends a greeting
                usage: /hello
        """.trimIndent() + "\n"

        val pluginClass = """
            package $pkg

            import org.bukkit.plugin.java.JavaPlugin

            class ${pluginName}Plugin : JavaPlugin() {
                override fun onEnable() {
                    logger.info("${request.projectName} enabled")
                    getCommand("hello")?.setExecutor { sender, _, _, _ ->
                        sender.sendMessage("Hello from ${request.projectName}")
                        true
                    }
                }

                override fun onDisable() {
                    logger.info("${request.projectName} disabled")
                }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("settings.gradle.kts", "rootProject.name = \"${request.projectName.replace(' ', '-')}\"\n"),
                ScaffoldFile("build.gradle.kts", build),
                ScaffoldFile("src/main/resources/plugin.yml", pluginYml),
                ScaffoldFile("src/main/kotlin/$pkgPath/${pluginName}Plugin.kt", pluginClass),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        """
                        A Paper server plugin for Minecraft 1.21.

                        ## Compatibility

                        The produced .jar is server-side only. It does not change the client and does not work on Bedrock.
                        """.trimIndent(),
                        "./gradlew build",
                        "Place the jar from build/libs into the server plugins folder",
                    ),
                ),
            ),
            entryPoint = "src/main/kotlin/$pkgPath/${pluginName}Plugin.kt",
        )
    }
}

class NativeModuleGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_MINECRAFT_NATIVE

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val name = Gen.sanitizePackage(request.packageName).replace('.', '_').lowercase()

        val cmake = """
            cmake_minimum_required(VERSION 3.20)
            project($name CXX)

            set(CMAKE_CXX_STANDARD 17)

            # Each ABI must be built separately; a binary from one ABI will not load on another.
            add_library($name SHARED src/module.cpp)
            target_include_directories($name PUBLIC include)

            add_executable(${name}_test tests/module_test.cpp)
            target_link_libraries(${name}_test PRIVATE $name)
            enable_testing()
            add_test(NAME ${name}_test COMMAND ${name}_test)
        """.trimIndent() + "\n"

        val header = """
            #pragma once

            #include <string>

            namespace $name {

            /// Returns the module version string.
            std::string version();

            /// Example hook entry point a loader would call.
            bool on_attach();

            }  // namespace $name
        """.trimIndent() + "\n"

        val source = """
            #include "module.hpp"

            namespace $name {

            std::string version() { return "1.0.0"; }

            bool on_attach() { return true; }

            }  // namespace $name
        """.trimIndent() + "\n"

        val test = """
            #include <cassert>
            #include <iostream>

            #include "module.hpp"

            int main() {
                assert(!${name}::version().empty());
                assert(${name}::on_attach());
                std::cout << "native module tests passed" << std::endl;
                return 0;
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("CMakeLists.txt", cmake),
                ScaffoldFile("include/module.hpp", header),
                ScaffoldFile("src/module.cpp", source),
                ScaffoldFile("tests/module_test.cpp", test),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        """
                        A native C++ module built with CMake.

                        ## Compatibility

                        The produced .so is built for one ABI (arm64-v8a, armeabi-v7a, x86_64).
                        A library built on Android will NOT load in a desktop game, and a desktop
                        build will NOT load on Android. Rebuild for each target, and confirm the
                        game exposes the symbols your hook needs - a stripped game binary usually
                        does not.
                        """.trimIndent(),
                        "cmake -S . -B build && cmake --build build",
                        "ctest --test-dir build --output-on-failure",
                    ),
                ),
            ),
            entryPoint = "src/module.cpp",
            notes = listOf("Cross-ABI binaries are incompatible; rebuild per target."),
        )
    }
}

class BedrockAddonGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_MINECRAFT_BEDROCK

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val namespace = Gen.sanitizePackage(request.packageName).replace('.', '_').lowercase()
        val uuid1 = java.util.UUID.nameUUIDFromBytes("$namespace-behaviour".toByteArray()).toString()
        val uuid2 = java.util.UUID.nameUUIDFromBytes("$namespace-behaviour-module".toByteArray()).toString()
        val uuid3 = java.util.UUID.nameUUIDFromBytes("$namespace-resource".toByteArray()).toString()
        val uuid4 = java.util.UUID.nameUUIDFromBytes("$namespace-resource-module".toByteArray()).toString()

        val behaviourManifest = """
            {
              "format_version": 2,
              "header": {
                "name": "${request.projectName} Behaviour",
                "description": "${request.projectName} behaviour pack",
                "uuid": "$uuid1",
                "version": [0, 1, 0],
                "min_engine_version": [1, 21, 0]
              },
              "modules": [
                {
                  "type": "data",
                  "uuid": "$uuid2",
                  "version": [0, 1, 0]
                }
              ]
            }
        """.trimIndent() + "\n"

        val resourceManifest = """
            {
              "format_version": 2,
              "header": {
                "name": "${request.projectName} Resources",
                "description": "${request.projectName} resource pack",
                "uuid": "$uuid3",
                "version": [0, 1, 0],
                "min_engine_version": [1, 21, 0]
              },
              "modules": [
                {
                  "type": "resources",
                  "uuid": "$uuid4",
                  "version": [0, 1, 0]
                }
              ]
            }
        """.trimIndent() + "\n"

        val item = """
            {
              "format_version": "1.21.0",
              "minecraft:item": {
                "description": {
                  "identifier": "$namespace:titan_item",
                  "menu_category": { "category": "items" }
                },
                "components": {
                  "minecraft:max_stack_size": 16,
                  "minecraft:icon": { "texture": "titan_item" }
                }
              }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("behaviour_pack/manifest.json", behaviourManifest),
                ScaffoldFile("behaviour_pack/items/titan_item.json", item),
                ScaffoldFile("resource_pack/manifest.json", resourceManifest),
                ScaffoldFile("resource_pack/textures/item_texture.json", """{ "resource_pack_name": "$namespace", "texture_name": "atlas.items", "texture_data": {} }""" + "\n"),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        """
                        A Bedrock Edition add-on: a behaviour pack plus a resource pack.

                        ## Compatibility

                        Add-ons are data, not code. They work on Minecraft Bedrock Edition only and
                        cannot run Java Edition code. Zip each pack folder and change the extension
                        to .mcpack to import it on a device.
                        """.trimIndent(),
                        null,
                        "Zip behaviour_pack and resource_pack, rename to .mcpack, then import",
                    ),
                ),
            ),
            entryPoint = "behaviour_pack/items/titan_item.json",
        )
    }
}

class RobloxLuauGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_ROBLOX_LUAU

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val projectJson = """
            {
              "name": "${request.projectName}",
              "tree": {
                "${'$'}className": "Folder",
                "Main": { "${'$'}path": "src/Main.luau" }
              }
            }
        """.trimIndent() + "\n"

        val module = """
            --!strict
            -- ${request.projectName}: a ModuleScript synchronised with Rojo.

            local Greeter = {}

            function Greeter.greet(name: string): string
                return string.format("Hello from %s", name)
            end

            return Greeter
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("default.project.json", projectJson),
                ScaffoldFile("src/Main.luau", module),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        """
                        A Luau module for Roblox.

                        ## Compatibility

                        Sync into Roblox Studio with the Rojo plugin. Luau cannot be executed on
                        the device; run it in Studio or a live experience.
                        """.trimIndent(),
                        null,
                        "rojo serve (with the Rojo Studio plugin connected)",
                    ),
                ),
            ),
            entryPoint = "src/Main.luau",
            notes = listOf("Execution happens inside Roblox Studio; TITAN only edits and lints the source."),
        )
    }
}

class FiveMResourceGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_GTA_FIVEM

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val resource = Gen.sanitizePackage(request.packageName).replace('.', '-').lowercase()

        val manifest = """
            fx_version 'cerulean'
            game 'gta5'

            author 'TITAN IDE'
            description '${request.projectName}'
            version '0.1.0'

            client_script 'client.lua'
            server_script 'server.lua'
        """.trimIndent() + "\n"

        val client = """
            -- ${request.projectName}: client resource

            RegisterNetEvent('titan:greet', function(name)
                local message = ('Hello from %s'):format(name)
                TriggerEvent('chat:addMessage', { args = { 'TITAN', message } })
            end)
        """.trimIndent() + "\n"

        val server = """
            -- ${request.projectName}: server resource

            AddEventHandler('playerJoining', function()
                local src = source
                TriggerClientEvent('titan:greet', src, GetPlayerName(src) or 'player')
            end)
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("fxmanifest.lua", manifest),
                ScaffoldFile("client.lua", client),
                ScaffoldFile("server.lua", server),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        """
                        A FiveM resource for GTA V multiplayer.

                        ## Compatibility

                        Runs on a FiveM server only. It does not affect single-player GTA V and cannot
                        be tested without a running FiveM server.
                        """.trimIndent(),
                        null,
                        "Copy the folder into your server resources and add 'ensure ${resource}' to server.cfg",
                    ),
                ),
            ),
            entryPoint = "client.lua",
        )
    }
}

class ScriptHookScriptGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_GTA_SCRIPT

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName)

        val csproj = """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <TargetFramework>net48</TargetFramework>
                <LangVersion>latest</LangVersion>
                <AssemblyName>${Gen.appClassName(request.projectName)}</AssemblyName>
                <RootNamespace>$pkg</RootNamespace>
              </PropertyGroup>
            </Project>
        """.trimIndent() + "\n"

        val script = """
            using System;
            using System.Windows.Forms;

            namespace $pkg
            {
                // ScriptHookVDotNet script for GTA V single-player.
                public class Main : GTA.Script
                {
                    public Main()
                    {
                        KeyDown += OnKeyDown;
                    }

                    private void OnKeyDown(object sender, KeyEventArgs e)
                    {
                        if (e.KeyCode == Keys.F7)
                        {
                            GTA.UI.Notification.Show("Hello from ${request.projectName}");
                        }
                    }
                }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("${Gen.appClassName(request.projectName)}.csproj", csproj),
                ScaffoldFile("Main.cs", script),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        """
                        A ScriptHookVDotNet script for GTA V single-player.

                        ## Compatibility

                        NOT BUILDABLE IN TITAN: compiling requires the .NET Framework SDK on Windows plus
                        the ScriptHookVDotNet and GTA V reference assemblies, none of which exist for Android.
                        TITAN provides the source, editing and linting only.
                        """.trimIndent(),
                        null,
                        "Build on Windows, then copy the .dll into the GTA V scripts folder",
                    ),
                ),
            ),
            entryPoint = "Main.cs",
            notes = listOf("Source-only template: compilation requires Windows and .NET Framework."),
        )
    }
}

class UnityScriptGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_UNITY_CSHARP

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val asmdef = """
            {
              "name": "${Gen.appClassName(request.projectName)}",
              "rootNamespace": "${Gen.sanitizePackage(request.packageName)}",
              "references": [],
              "includePlatforms": [],
              "excludePlatforms": [],
              "allowUnsafeCode": false,
              "overrideReferences": false,
              "precompiledReferences": [],
              "autoReferenced": true,
              "defineConstraints": [],
              "versionDefines": [],
              "noEngineReferences": false
            }
        """.trimIndent() + "\n"

        val behaviour = """
            using UnityEngine;

            namespace ${Gen.sanitizePackage(request.packageName)}
            {
                /// <summary>${request.projectName} behaviour.</summary>
                public class TitanBehaviour : MonoBehaviour
                {
                    [SerializeField] private float speed = 1f;

                    private void Update()
                    {
                        transform.Rotate(Vector3.up, speed * Time.deltaTime);
                    }
                }
            }
        """.trimIndent() + "\n"

        val packageManifest = """
            {
              "name": "com.titanide.${Gen.sanitizePackage(request.packageName).lowercase()}",
              "version": "0.1.0",
              "displayName": "${request.projectName}",
              "unity": "2022.3"
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("package.json", packageManifest),
                ScaffoldFile("Runtime/TitanBehaviour.cs", behaviour),
                ScaffoldFile("Runtime/${Gen.appClassName(request.projectName)}.asmdef", asmdef),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        """
                        A Unity C# package with a MonoBehaviour.

                        ## Compatibility

                        NOT BUILDABLE IN TITAN: compiling requires the Unity Editor. TITAN provides the
                        source, assembly definitions and package manifest; add the folder to a Unity
                        project's Packages directory.
                        """.trimIndent(),
                        null,
                        null,
                    ),
                ),
            ),
            entryPoint = "Runtime/TitanBehaviour.cs",
            notes = listOf("Source-only template: compilation requires the Unity Editor."),
        )
    }
}

class UnrealPluginGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_UNREAL_CPP

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val moduleName = Gen.appClassName(request.projectName)
        val pluginName = "${moduleName}Plugin"

        val uplugin = """
            {
              "FileVersion": 3,
              "Version": 1,
              "VersionName": "0.1.0",
              "FriendlyName": "${request.projectName}",
              "Description": "${request.projectName} Unreal plugin",
              "Category": "Other",
              "CreatedBy": "TITAN IDE",
              "Modules": [
                {
                  "Name": "$moduleName",
                  "Type": "Runtime",
                  "LoadingPhase": "Default"
                }
              ]
            }
        """.trimIndent() + "\n"

        val buildCs = """
            using UnrealBuildTool;

            public class $moduleName : ModuleRules
            {
                public $moduleName(ReadOnlyTargetRules Target) : base(Target)
                {
                    PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;
                    PublicDependencyModuleNames.AddRange(new[] { "Core", "CoreUObject", "Engine" });
                }
            }
        """.trimIndent() + "\n"

        val moduleCpp = """
            #include "Modules/ModuleManager.h"

            IMPLEMENT_MODULE(FDefaultModuleImpl, $moduleName)
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("$pluginName.uplugin", uplugin),
                ScaffoldFile("Source/$moduleName/$moduleName.Build.cs", buildCs),
                ScaffoldFile("Source/$moduleName/Private/$moduleName.cpp", moduleCpp),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        """
                        An Unreal Engine 5 C++ plugin.

                        ## Compatibility

                        NOT BUILDABLE IN TITAN: building requires UnrealBuildTool and a desktop C++
                        toolchain. TITAN provides the plugin descriptor and module sources.
                        """.trimIndent(),
                        null,
                        null,
                    ),
                ),
            ),
            entryPoint = "Source/$moduleName/Private/$moduleName.cpp",
            notes = listOf("Source-only template: building requires UnrealBuildTool."),
        )
    }
}
