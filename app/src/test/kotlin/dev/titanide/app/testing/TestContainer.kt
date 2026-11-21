package dev.titanide.app.testing

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource

/**
 * Hands out [TitanContainer]s that are closed when the test finishes.
 *
 * A container owns an open Room database and a collecting scope. Robolectric points every
 * test class at the same app data directory, so a container left open kept its SQLite
 * connection and a later class eventually failed with `SQLITE_BUSY`. Closing happens in
 * [CloseContainersRule] rather than in each `@After` because a container is usually built in a
 * field initializer, which runs before any `@Before`.
 */
object TestContainers {

    private val open = java.util.Collections.synchronizedList(mutableListOf<TitanContainer>())

    fun create(gitHubApiBase: String = "https://api.github.com"): TitanContainer =
        TitanContainer(ApplicationProvider.getApplicationContext(), gitHubApiBase).also { open += it }

    fun closeAll() {
        val closing = synchronized(open) {
            val copy = open.toList()
            open.clear()
            copy
        }
        // close() cancels the scope but cannot wait for children, and a query that is still
        // running when the database closes fails with "connection is closed" in the *next*
        // test. Cancel and join first, then close.
        closing.forEach { container ->
            runBlocking {
                withTimeoutOrNull(5_000) {
                    container.appScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
            container.close()
        }
    }
}

/** Closes every container [TestContainers.create] handed out during the test. */
class CloseContainersRule : ExternalResource() {
    override fun after() = TestContainers.closeAll()
}
