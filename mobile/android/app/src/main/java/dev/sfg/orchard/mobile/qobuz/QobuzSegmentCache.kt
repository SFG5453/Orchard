package dev.sfg.orchard.mobile.qobuz

/** Bounds retained lossless audio across tracks, seeks, and concurrent range requests. */
internal class QobuzSegmentCache(private val maxBytes: Int) {
    private val entries = LinkedHashMap<Pair<String, Int>, ByteArray>(16, 0.75f, true)
    private var retainedBytes = 0

    init {
        require(maxBytes >= 0)
    }

    // Serialize misses as well as lookups: concurrent prefetch ranges must not each
    // download and decrypt another copy of the same multi-megabyte segment.
    @Synchronized
    fun getOrLoad(playbackId: String, number: Int, load: () -> ByteArray): ByteArray {
        val key = playbackId to number
        entries[key]?.let { return it }
        val bytes = load()
        if (bytes.size > maxBytes) return bytes
        val iterator = entries.entries.iterator()
        while (retainedBytes > maxBytes - bytes.size && iterator.hasNext()) {
            retainedBytes -= iterator.next().value.size
            iterator.remove()
        }
        entries[key] = bytes
        retainedBytes += bytes.size
        return bytes
    }
}
