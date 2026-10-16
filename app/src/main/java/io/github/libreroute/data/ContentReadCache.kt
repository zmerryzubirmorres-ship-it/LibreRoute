package io.github.libreroute.data

/** Reuses decoded data only while the complete encrypted file content matches. */
internal class ContentReadCache<T>(private val capacity: Int = 8) {
    private data class Entry<T>(val bytes: ByteArray, val value: T)
    private val entries = LinkedHashMap<String, Entry<T>>()

    @Synchronized
    fun read(path: String, bytes: ByteArray, decode: () -> T): T {
        entries[path]?.takeIf { it.bytes.contentEquals(bytes) }?.let { return it.value }
        val value = decode() // Do not cache a failed decryption or malformed file.
        if (entries.size >= capacity) entries.remove(entries.keys.first())
        entries[path] = Entry(bytes.copyOf(), value)
        return value
    }

    @Synchronized
    fun invalidate(path: String) { entries.remove(path) }
}
