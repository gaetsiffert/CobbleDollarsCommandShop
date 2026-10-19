package fr.cobbledollars.commandshops.client.admin

/** Screen-local navigation history. Loading placeholders cannot replace a complete view's position. */
internal class AdminViewScrollState(private val capacity: Int = 256) {
    private val positions = object : LinkedHashMap<List<String>, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, Int>?) = size > capacity
    }
    private var activeKey: List<String>? = null
    private var activeRememberable = false

    init { require(capacity > 0) }

    fun show(key: List<String>, previousPosition: Int, previousLaidOut: Boolean, rememberable: Boolean): Int {
        if (activeRememberable && previousLaidOut) {
            activeKey?.let { positions[it] = previousPosition.coerceAtLeast(0) }
        }
        activeKey = key.toList()
        activeRememberable = rememberable
        return positions[key] ?: 0
    }

    fun discardWhere(predicate: (List<String>) -> Boolean) {
        positions.keys.removeAll(predicate)
        if (activeKey?.let(predicate) == true) {
            activeKey = null
            activeRememberable = false
        }
    }
}
