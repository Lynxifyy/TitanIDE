package dev.titanide.core.editor

import dev.titanide.core.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rename was the one navigation feature the requirement called out as destructive, so it is
 * planned in full before anything is written and refuses to touch text that only looks like a
 * reference.
 */
class RenamePlannerTest {

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

    private fun plan(
        sources: Map<String, String>,
        oldName: String,
        newName: String,
    ) = RenamePlanner.plan(
        sources,
        sources.keys.associateWith { Language.KOTLIN },
        oldName,
        newName,
    )

    @Test
    fun `a rename rewrites the declaration and every use`() {
        val result = plan(
            mapOf("UserService.kt" to service, "Controller.kt" to caller),
            "UserService",
            "AccountService",
        )

        assertNull("the plan should be applicable: ${result.rejection}", result.rejection)
        assertEquals(2, result.totalOccurrences)
        val serviceChange = result.changedFiles.first { it.filePath == "UserService.kt" }
        assertTrue(serviceChange.after.contains("class AccountService"))
        val callerChange = result.changedFiles.first { it.filePath == "Controller.kt" }
        assertTrue(
            "the constructor call must be renamed too: ${callerChange.after}",
            callerChange.after.contains("AccountService()"),
        )
        assertFalse(
            "the old name must be gone: ${callerChange.after}",
            callerChange.after.contains("UserService"),
        )
    }

    @Test
    fun `the plan keeps the original text so it can be previewed and reverted`() {
        val result = plan(mapOf("UserService.kt" to service), "UserService", "AccountService")
        val change = result.changedFiles.single()
        assertEquals("the before text must be untouched", service, change.before)
        assertTrue("the after text must differ", change.after != change.before)
    }

    @Test
    fun `a same-named word in a comment is left alone`() {
        val source = """
            class Widget {
                // Widget is the thing this class describes
                fun build(): Widget = Widget()
            }
        """.trimIndent()

        val result = plan(mapOf("Widget.kt" to source), "Widget", "Gadget")

        val after = result.changedFiles.single().after
        assertTrue("the class must be renamed", after.contains("class Gadget"))
        assertTrue("the return type must be renamed", after.contains(": Gadget"))
        assertTrue(
            "the comment must be left as written: $after",
            after.contains("// Widget is the thing"),
        )
    }

    @Test
    fun `a same-named word in a string is left alone`() {
        val source = """
            class Thing {
                fun label(): String = "Thing"
            }
        """.trimIndent()

        val after = plan(mapOf("Thing.kt" to source), "Thing", "Object").changedFiles.single().after
        assertTrue("the class must be renamed: $after", after.contains("class Object"))
        assertTrue("the string literal must survive: $after", after.contains("\"Thing\""))
    }

    @Test
    fun `a longer identifier is not renamed`() {
        val source = """
            class Counter {
                val count = 0
                fun counter(): Int = count
            }
        """.trimIndent()

        val after = plan(mapOf("Counter.kt" to source), "count", "total").changedFiles.single().after
        assertTrue("the property must be renamed: $after", after.contains("val total"))
        assertTrue("the class name must survive: $after", after.contains("class Counter"))
        assertTrue("the method name must survive: $after", after.contains("fun counter"))
    }

    @Test
    fun `a file with no references is reported as unchanged`() {
        val result = plan(
            mapOf("A.kt" to "class Alpha {}", "B.kt" to "class Beta {}"),
            "Alpha",
            "Gamma",
        )

        assertEquals("only one file should change", 1, result.changedFiles.size)
        val untouched = result.files.first { it.filePath == "B.kt" }
        assertEquals(0, untouched.occurrences)
        assertFalse(untouched.changed)
    }

    @Test
    fun `an invalid new name is rejected`() {
        val result = plan(mapOf("A.kt" to "class Alpha {}"), "Alpha", "not a name")
        assertNotNull("a name with spaces must be rejected", result.rejection)
        assertTrue(result.rejection!!.contains("not a valid identifier"))
    }

    @Test
    fun `a name starting with a digit is rejected`() {
        assertNotNull(plan(mapOf("A.kt" to "class Alpha {}"), "Alpha", "2Fast").rejection)
    }

    @Test
    fun `renaming to the same name is rejected`() {
        assertNotNull(plan(mapOf("A.kt" to "class Alpha {}"), "Alpha", "Alpha").rejection)
    }

    @Test
    fun `renaming a symbol nothing uses is rejected`() {
        val result = plan(mapOf("A.kt" to "class Alpha {}"), "Missing", "Found")
        assertNotNull("there is nothing to rename", result.rejection)
        assertTrue(result.rejection!!.contains("Nothing in the project"))
    }

    @Test
    fun `an underscore and digits are allowed in a new name`() {
        assertTrue(RenamePlan.isValidIdentifier("_private2"))
        assertFalse(RenamePlan.isValidIdentifier(""))
        assertFalse(RenamePlan.isValidIdentifier("has space"))
    }

    @Test
    fun `every occurrence in a file is counted`() {
        val source = """
            class Dup {
                fun a(): Dup = Dup()
                fun b(): Dup = Dup()
            }
        """.trimIndent()

        val result = plan(mapOf("Dup.kt" to source), "Dup", "Once")
        assertEquals(
            "class, two return types and two constructor calls",
            5,
            result.changedFiles.single().occurrences,
        )
        assertFalse("no occurrence may be left behind: ${result.changedFiles.single().after}",
            result.changedFiles.single().after.contains("Dup"))
    }
}
