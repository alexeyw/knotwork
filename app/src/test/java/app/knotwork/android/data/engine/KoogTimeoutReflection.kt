package app.knotwork.android.data.engine

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig

/**
 * Reads the [ConnectionTimeoutConfig] a Koog provider client was built with.
 *
 * Walks the class hierarchy for the client's `settings` property and reads its
 * `timeoutConfig`. Reflection is the only route — Koog keeps `settings` private — but it
 * fails loudly rather than silently if Koog renames the field on an upgrade.
 *
 * Only clients that keep a `settings` object answer it (OpenAI, Anthropic, Google,
 * DeepSeek). `OllamaClient` hands the values straight to its HTTP client and keeps
 * none, which is why `KoogClientTimeoutKonsistTest` checks the construction sites
 * instead.
 *
 * @param client A raw (not retry-wrapped) Koog provider client.
 * @return The timeout configuration the client carries.
 */
internal fun koogTimeoutsOf(client: Any): ConnectionTimeoutConfig {
    var type: Class<*>? = client.javaClass
    while (type != null) {
        val field = type.declaredFields.firstOrNull { it.name == "settings" }
        if (field != null) {
            field.isAccessible = true
            val settings = requireNotNull(field.get(client)) { "settings was null on $type" }
            val timeoutField = generateSequence(settings.javaClass as Class<*>?) { it.superclass }
                .mapNotNull { klass -> klass.declaredFields.firstOrNull { it.name == "timeoutConfig" } }
                .firstOrNull()
                ?: error("no timeoutConfig on ${settings.javaClass} — did Koog rename it?")
            timeoutField.isAccessible = true
            return timeoutField.get(settings) as ConnectionTimeoutConfig
        }
        type = type.superclass
    }
    error("no settings field on ${client.javaClass} — did Koog rename it?")
}
