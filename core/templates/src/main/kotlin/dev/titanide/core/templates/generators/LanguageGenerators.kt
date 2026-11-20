package dev.titanide.core.templates.generators

import dev.titanide.core.templates.Gen
import dev.titanide.core.templates.ProjectGenerator
import dev.titanide.core.templates.ScaffoldFile
import dev.titanide.core.templates.ScaffoldRequest
import dev.titanide.core.templates.ScaffoldResult
import dev.titanide.core.templates.TemplateCatalog

class PythonGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_PYTHON

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val module = Gen.sanitizePackage(request.packageName).replace('.', '_').lowercase()
        val main = """
            \"\"\"Entry point for ${request.projectName}.\"\"\"


            def greeting(name: str) -> str:
                return f"Hello from {name}"


            def main() -> None:
                print(greeting("${request.projectName}"))


            if __name__ == "__main__":
                main()
        """.trimIndent() + "\n"

        val test = """
            from $module.main import greeting


            def test_greeting_includes_name() -> None:
                assert greeting("Titan") == "Hello from Titan"
        """.trimIndent() + "\n"

        val pyproject = """
            [project]
            name = "$module"
            version = "0.1.0"
            description = "${request.projectName}"
            requires-python = ">=3.9"

            [project.scripts]
            $module = "$module.main:main"

            [tool.pytest.ini_options]
            testpaths = ["tests"]
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("pyproject.toml", pyproject),
                ScaffoldFile("$module/__init__.py", ""),
                ScaffoldFile("$module/main.py", main),
                ScaffoldFile("tests/test_main.py", test),
                ScaffoldFile("requirements.txt", "# Runtime dependencies\n"),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A Python application.", "pip install -e .", "python -m $module.main"),
                ),
            ),
            entryPoint = "$module/main.py",
        )
    }
}

class NodeGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_NODE

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName).replace('.', '-').lowercase()

        val packageJson = """
            {
              "name": "$pkg",
              "version": "0.1.0",
              "private": true,
              "type": "module",
              "description": "${request.projectName}",
              "main": "src/index.js",
              "scripts": {
                "start": "node src/index.js",
                "test": "node --test"
              },
              "engines": {
                "node": ">=18"
              }
            }
        """.trimIndent() + "\n"

        val index = """
            export function greeting(name) {
              return `Hello from ${'$'}{name}`;
            }

            if (import.meta.url === `file://${'$'}{process.argv[1]}`) {
              console.log(greeting("${request.projectName}"));
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
                    Gen.readme(request.projectName, "A Node.js application.", "npm install", "npm start"),
                ),
            ),
            entryPoint = "src/index.js",
        )
    }
}

class RustGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_RUST

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val crate = Gen.sanitizePackage(request.packageName).replace('.', '_').lowercase()
        val cargoToml = """
            [package]
            name = "$crate"
            version = "0.1.0"
            edition = "2021"
            description = "${request.projectName}"

            [dependencies]
        """.trimIndent() + "\n"

        val main = """
            /// Returns a greeting for the given name.
            pub fn greeting(name: &str) -> String {
                format!("Hello from {}", name)
            }

            fn main() {
                println!("{}", greeting("${request.projectName}"));
            }

            #[cfg(test)]
            mod tests {
                use super::*;

                #[test]
                fn greeting_includes_name() {
                    assert_eq!(greeting("Titan"), "Hello from Titan");
                }
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("Cargo.toml", cargoToml),
                ScaffoldFile("src/main.rs", main),
                ScaffoldFile(".gitignore", Gen.gitignore() + "Cargo.lock\n"),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A Rust binary crate.", "cargo build", "cargo run"),
                ),
            ),
            entryPoint = "src/main.rs",
        )
    }
}

class GoGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_GO

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val module = Gen.sanitizePackage(request.packageName).lowercase()
        val goMod = """
            module $module

            go 1.21
        """.trimIndent() + "\n"

        val main = """
            package main

            import "fmt"

            // Greeting returns a greeting for the given name.
            func Greeting(name string) string {
            	return fmt.Sprintf("Hello from %s", name)
            }

            func main() {
            	fmt.Println(Greeting("${request.projectName}"))
            }
        """.trimIndent() + "\n"

        val test = """
            package main

            import "testing"

            func TestGreeting(t *testing.T) {
            	if got := Greeting("Titan"); got != "Hello from Titan" {
            		t.Fatalf("unexpected greeting: %s", got)
            	}
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("go.mod", goMod),
                ScaffoldFile("main.go", main),
                ScaffoldFile("main_test.go", test),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A Go module.", "go build ./...", "go run ."),
                ),
            ),
            entryPoint = "main.go",
        )
    }
}

class CMakeGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_CMAKE

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val target = Gen.sanitizePackage(request.packageName).replace('.', '_').lowercase()

        val cmake = """
            cmake_minimum_required(VERSION 3.20)
            project(${request.projectName.replace(' ', '_')} CXX)

            set(CMAKE_CXX_STANDARD 17)
            set(CMAKE_CXX_STANDARD_REQUIRED ON)

            add_library(core src/core.cpp)
            target_include_directories(core PUBLIC include)

            add_executable($target src/main.cpp)
            target_link_libraries($target PRIVATE core)

            enable_testing()
            add_executable(core_tests tests/core_test.cpp)
            target_link_libraries(core_tests PRIVATE core)
            add_test(NAME core_tests COMMAND core_tests)
        """.trimIndent() + "\n"

        val header = """
            #pragma once

            #include <string>

            namespace ${target} {

            /// Returns a greeting for the given name.
            std::string greeting(const std::string& name);

            }  // namespace ${target}
        """.trimIndent() + "\n"

        val core = """
            #include "core.hpp"

            namespace ${target} {

            std::string greeting(const std::string& name) {
                return "Hello from " + name;
            }

            }  // namespace ${target}
        """.trimIndent() + "\n"

        val main = """
            #include <iostream>

            #include "core.hpp"

            int main() {
                std::cout << ${target}::greeting("${request.projectName}") << std::endl;
                return 0;
            }
        """.trimIndent() + "\n"

        val test = """
            #include <cassert>
            #include <iostream>

            #include "core.hpp"

            int main() {
                assert(${target}::greeting("Titan") == "Hello from Titan");
                std::cout << "all tests passed" << std::endl;
                return 0;
            }
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("CMakeLists.txt", cmake),
                ScaffoldFile("include/core.hpp", header),
                ScaffoldFile("src/core.cpp", core),
                ScaffoldFile("src/main.cpp", main),
                ScaffoldFile("tests/core_test.cpp", test),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(
                        request.projectName,
                        "A C++ project built with CMake.",
                        "cmake -S . -B build && cmake --build build",
                        "ctest --test-dir build --output-on-failure",
                    ),
                ),
            ),
            entryPoint = "src/main.cpp",
        )
    }
}
