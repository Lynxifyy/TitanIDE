package dev.titanide.app.extensions

/**
 * The capabilities a built-in extension can switch off. Each one is checked at the point the
 * feature is actually used, so turning an extension off has a visible effect instead of only
 * changing a row in a list.
 */
enum class TitanCapability(val extensionId: String, val label: String) {
    AI("titan.ai", "TITAN Intelligence"),
    REMOTE_BUILD("titan.remote-build", "Remote build backends"),
    DEV_TOOLS("titan.tools", "Developer tools"),
    ;

    companion object {
        fun forExtension(id: String): List<TitanCapability> = entries.filter { it.extensionId == id }
    }
}

/**
 * Answers whether a capability is currently available. Extensions that were never toggled
 * default to enabled, because a fresh install must not silently lose features.
 */
fun interface CapabilityGate {
    fun isEnabled(capability: TitanCapability): Boolean

    companion object {
        val ALL_ENABLED = CapabilityGate { true }
    }
}
