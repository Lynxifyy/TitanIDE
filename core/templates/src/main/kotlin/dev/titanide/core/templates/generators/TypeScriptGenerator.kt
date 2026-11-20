package dev.titanide.core.templates.generators

import dev.titanide.core.templates.Gen
import dev.titanide.core.templates.ProjectGenerator
import dev.titanide.core.templates.ScaffoldFile
import dev.titanide.core.templates.ScaffoldRequest
import dev.titanide.core.templates.ScaffoldResult
import dev.titanide.core.templates.TemplateCatalog

class TypeScriptGenerator : ProjectGenerator {
    override val id = TemplateCatalog.GEN_TS_NODE

    override fun generate(request: ScaffoldRequest): ScaffoldResult {
        val pkg = Gen.sanitizePackage(request.packageName).replace('.', '-').lowercase()

        val packageJson = """
            {
              "name": "$pkg",
              "version": "0.1.0",
              "private": true,
              "type": "module",
              "description": "${request.projectName}",
              "main": "dist/index.js",
              "scripts": {
                "build": "tsc -p tsconfig.json",
                "start": "node dist/index.js",
                "test": "node --test dist/**/*.test.js"
              },
              "devDependencies": {
                "typescript": "^5.4.0",
                "@types/node": "^20.0.0"
              },
              "engines": {
                "node": ">=18"
              }
            }
        """.trimIndent() + "\n"

        val tsconfig = """
            {
              "compilerOptions": {
                "target": "ES2022",
                "module": "NodeNext",
                "moduleResolution": "NodeNext",
                "outDir": "dist",
                "rootDir": "src",
                "strict": true,
                "declaration": true,
                "sourceMap": true,
                "esModuleInterop": true,
                "skipLibCheck": true
              },
              "include": ["src/**/*.ts"]
            }
        """.trimIndent() + "\n"

        val index = """
            export function greeting(name: string): string {
              return `Hello from ${'$'}{name}`;
            }

            console.log(greeting("${request.projectName}"));
        """.trimIndent() + "\n"

        val test = """
            import assert from "node:assert/strict";
            import test from "node:test";
            import { greeting } from "./index.js";

            test("greeting includes the name", () => {
              assert.equal(greeting("Titan"), "Hello from Titan");
            });
        """.trimIndent() + "\n"

        return ScaffoldResult(
            files = listOf(
                ScaffoldFile("package.json", packageJson),
                ScaffoldFile("tsconfig.json", tsconfig),
                ScaffoldFile("src/index.ts", index),
                ScaffoldFile("src/index.test.ts", test),
                ScaffoldFile(".gitignore", Gen.gitignore()),
                ScaffoldFile(
                    "README.md",
                    Gen.readme(request.projectName, "A TypeScript project.", "npm install && npm run build", "npm start"),
                ),
            ),
            entryPoint = "src/index.ts",
        )
    }
}
