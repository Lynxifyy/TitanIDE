package dev.titanide.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitanRoutesTest {

    @Test
    fun `seven primary destinations are defined`() {
        assertEquals(7, TitanDestination.primary.size)
    }

    @Test
    fun `primary destination routes are unique`() {
        val routes = TitanDestination.primary.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `unknown route falls back to home`() {
        assertEquals(TitanDestination.HOME, TitanDestination.fromRoute("nope"))
        assertEquals(TitanDestination.HOME, TitanDestination.fromRoute(null))
    }

    @Test
    fun `known route resolves to its destination`() {
        TitanDestination.entries.forEach { destination ->
            assertEquals(destination, TitanDestination.fromRoute(destination.route))
        }
    }

    @Test
    fun `project detail route embeds the id`() {
        assertEquals("project/abc-123", TitanRoutes.projectDetail("abc-123"))
    }

    @Test
    fun `github repo route replaces slash with colon`() {
        val route = TitanRoutes.githubRepo("owner/repo")

        assertEquals("github/repo/owner:repo", route)
        assertTrue(!route.substringAfterLast('/').contains('/'))
    }

    @Test
    fun `build route takes an optional project id`() {
        assertEquals("build?projectId={projectId}", TitanRoutes.BUILD)
    }

    @Test
    fun `detail routes do not shadow primary routes`() {
        val primary = TitanDestination.entries.map { it.route }.toSet()
        val detailRoutes = listOf(
            TitanRoutes.CREATE_PROJECT,
            TitanRoutes.PROJECT_DETAIL,
            TitanRoutes.GITHUB,
            TitanRoutes.GIT,
            TitanRoutes.TOOLS,
            TitanRoutes.API_TESTER,
            TitanRoutes.ASSET_STUDIO,
            TitanRoutes.DATABASE_VIEWER,
            TitanRoutes.UI_PREVIEW,
            TitanRoutes.DOCS,
        )
        detailRoutes.forEach { route ->
            val head = route.substringBefore('?').substringBefore('/')
            assertTrue("route $route shadows a primary destination", head !in primary)
        }
    }
}
