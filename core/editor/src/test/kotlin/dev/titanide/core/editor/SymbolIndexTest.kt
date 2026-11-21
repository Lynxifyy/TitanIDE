package dev.titanide.core.editor

import dev.titanide.core.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation used to be impossible: the editor could highlight and complete, but nothing
 * answered "where is this declared" or "who uses it". These cases pin the index that backs
 * go-to-definition and find-references.
 */
class SymbolIndexTest {

    private val service = """
        package demo

        class UserService {
            fun loadUser(id: String): String {
                return id
            }
        }
    """.trimIndent()

    private val caller = """
        package demo

        class Controller {
            private val service = UserService()

            fun handle(id: String) {
                service.loadUser(id)
            }
        }
    """.trimIndent()

    private fun index(): SymbolIndex = SymbolIndex().apply {
        put("src/UserService.kt", service, Language.KOTLIN)
        put("src/Controller.kt", caller, Language.KOTLIN)
    }

    @Test
    fun `declarations are extracted across files`() {
        val names = index().allSymbols().map { it.symbol.name }
        assertTrue("the class should be indexed: $names", "UserService" in names)
        assertTrue("the method should be indexed: $names", "loadUser" in names)
        assertTrue("the property should be indexed: $names", "service" in names)
    }

    @Test
    fun `go to definition finds the declaring file`() {
        val index = index()
        val offset = caller.indexOf("UserService()")
        val definition = index.definitionAt("src/Controller.kt", offset)

        assertNotNull("UserService is declared in another file and must resolve", definition)
        assertEquals("src/UserService.kt", definition!!.symbol.filePath)
        assertEquals("UserService", definition.symbol.name)
    }

    @Test
    fun `a same-file declaration wins over a remote one`() {
        val index = SymbolIndex()
        index.put(
            "src/A.kt",
            "class A {\n    fun shared() {}\n}",
            Language.KOTLIN,
        )
        index.put(
            "src/B.kt",
            "class B {\n    fun shared() {}\n    fun call() { shared() }\n}",
            Language.KOTLIN,
        )

        val text = "class B {\n    fun shared() {}\n    fun call() { shared() }\n}"
        val callSite = text.indexOf("shared() }")
        val definition = index.definitionAt("src/B.kt", callSite)
        assertEquals(
            "a local declaration must shadow one from another file",
            "src/B.kt",
            definition?.symbol?.filePath,
        )
    }

    @Test
    fun `an unknown identifier has no definition`() {
        assertNull(index().definitionAt("src/Controller.kt", caller.indexOf("package")))
    }

    @Test
    fun `find references returns every use including the declaration`() {
        val references = index().referencesTo("UserService")

        assertEquals(
            "the declaration and the constructor call should both be found",
            2,
            references.size,
        )
        assertTrue("the declaration site must be marked", references.any { it.isDeclaration })
        assertTrue("the use site must be marked", references.any { !it.isDeclaration })
    }

    @Test
    fun `find references does not match a longer identifier`() {
        val index = SymbolIndex()
        index.put(
            "src/C.kt",
            "class C {\n    val count = 1\n    fun use() { counter() }\n    fun counter() {}\n}",
            Language.KOTLIN,
        )

        val references = index.referencesTo("count")
        assertEquals(
            "`counter` must not be reported as a reference to `count`: ${references.map { it.lineText }}",
            1,
            references.size,
        )
    }

    @Test
    fun `a name inside a comment is not a reference`() {
        val index = SymbolIndex()
        index.put(
            "src/D.kt",
            "class D {\n    fun alpha() {}\n}\n// alpha is explained here",
            Language.KOTLIN,
        )

        val references = index.referencesTo("alpha")
        assertEquals(
            "prose in a comment must not count as a reference: ${references.map { it.lineText }}",
            1,
            references.size,
        )
        assertFalse(references.single().lineText.contains("//"))
    }

    @Test
    fun `symbol search ranks exact matches first`() {
        val index = SymbolIndex()
        index.put(
            "src/E.kt",
            """
            class Loader {}
            class UserLoader {}
            class LoadUser {}
            """.trimIndent(),
            Language.KOTLIN,
        )

        val results = index.search("LoadUser")
        assertEquals("LoadUser", results.first().symbol.name)
    }

    @Test
    fun `search is case insensitive and bounded`() {
        val index = SymbolIndex()
        index.put("src/F.kt", (1..80).joinToString("\n") { "class Thing$it {}" }, Language.KOTLIN)

        val results = index.search("thing", limit = 10)
        assertEquals("the limit must be honoured", 10, results.size)
        assertTrue("matching must ignore case", results.all { it.symbol.name.startsWith("Thing") })
    }

    @Test
    fun `an empty query returns nothing`() {
        assertTrue(index().search("   ").isEmpty())
    }

    @Test
    fun `re-indexing a file replaces its old declarations`() {
        val index = SymbolIndex()
        index.put("src/G.kt", "class Old {}", Language.KOTLIN)
        index.put("src/G.kt", "class New {}", Language.KOTLIN)

        val names = index.allSymbols().map { it.symbol.name }
        assertTrue("New should be indexed", "New" in names)
        assertFalse("the replaced declaration must not linger", "Old" in names)
        assertEquals("re-indexing must not add a second entry", 1, index.size)
    }

    @Test
    fun `a removed file leaves no symbols behind`() {
        val index = index()
        index.remove("src/UserService.kt")
        assertEquals("only Controller should remain", 1, index.size)
        assertTrue(index.declarationsOf("UserService").isEmpty())
    }
}
