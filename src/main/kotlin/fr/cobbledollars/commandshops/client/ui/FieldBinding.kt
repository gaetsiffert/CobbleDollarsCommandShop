// Reused from Cobblemon Expeditions admin UI (original project: ZIPender; admin tooling: Gaetan).
package fr.cobbledollars.commandshops.client.ui

interface FieldBinding<T> {
    val key: String

    fun get(): T

    fun set(value: T)
}

class LambdaFieldBinding<T>(
    override val key: String,
    private val getter: () -> T,
    private val setter: (T) -> Unit
) : FieldBinding<T> {
    override fun get(): T = getter()

    override fun set(value: T) = setter(value)
}
