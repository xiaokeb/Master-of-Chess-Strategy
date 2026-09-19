package com.masterofchessstrategy.custom

/** Canonical lowercase hex used by navigation and the persisted custom-position variant. */
internal object CustomPositionStateCodec {
    fun encode(bytes: ByteArray): String {
        require(bytes.size in 1..MAX_POSITION_BYTES)
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun decode(value: String): ByteArray {
        require(value.length % 2 == 0 && value.length / 2 in 1..MAX_POSITION_BYTES)
        return ByteArray(value.length / 2) { index ->
            val high = requireNotNull(value[index * 2].digitToIntOrNull(16)) {
                "Invalid custom position"
            }
            val low = requireNotNull(value[index * 2 + 1].digitToIntOrNull(16)) {
                "Invalid custom position"
            }
            ((high shl 4) or low).toByte()
        }.also {
            require(encode(it) == value)
        }
    }

    fun sessionVariant(position: ByteArray): String = PREFIX + encode(position)

    fun decodeSessionVariant(value: String): ByteArray {
        require(value.startsWith(PREFIX))
        return decode(value.removePrefix(PREFIX))
    }

    const val PREFIX = "custom:"
    private const val MAX_POSITION_BYTES = 128
}
