package app.knotwork.android.domain.engine.golden

import java.lang.reflect.Proxy

/**
 * Strict stand-in for an interface whose members a golden run must not reach: every call
 * raises a harness violation.
 *
 * Fakes in the harness are written as `object : X by goldenStrict(log) { override … }`, so only
 * the members a run path is known to use are answered. A MockK mock would throw
 * `MockKException` instead — an ordinary `Exception` that the engine's best-effort `catch`
 * blocks swallow, so a refactoring that started calling a new member inside one of them would
 * leave the trace unchanged. A violation is an `AssertionError` and is also kept in the log's
 * list, which the run asserts empty even when something caught `Throwable`.
 *
 * `toString`, `hashCode` and `equals` answer as for any object; nothing in a run depends on
 * them, and logging or a collection may call them.
 *
 * @param log The run's event log.
 * @return A proxy implementing [T].
 */
internal inline fun <reified T : Any> goldenStrict(log: GoldenEventLog): T {
    val type = T::class.java
    val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
        when (method.name) {
            "toString" -> "golden-strict ${type.simpleName}"
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.firstOrNull()
            else -> log.violation(
                "${type.simpleName}.${method.name} is not on any golden run path; extend the harness deliberately",
            )
        }
    }
    return type.cast(proxy)
}
