package com.fograin.launcher.community

import kotlin.random.Random

/** MiAuth session/URL construction, isolated for testing. */
object CommunityAuth {
    fun newSession(): String {
        val bytes = Random.nextBytes(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return buildString {
            append(hex, 0, 8)
            append('-')
            append(hex, 8, 12)
            append('-')
            append(hex, 12, 16)
            append('-')
            append(hex, 16, 20)
            append('-')
            append(hex, 20, 32)
        }
    }

    fun url(session: String): String {
        val permissions = CommunityConfig.PERMISSIONS.joinToString(",")
        return "${CommunityConfig.BASE_URL}/miauth/$session" +
            "?name=${encode(CommunityConfig.APP_NAME)}" +
            "&permission=${encode(permissions)}"
    }

    private fun encode(value: String): String = buildString {
        val hex = "0123456789ABCDEF"
        value.encodeToByteArray().forEach { byte ->
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            if (char.isLetterOrDigit() || char in "-._~") {
                append(char)
            } else {
                append('%')
                append(hex[code shr 4])
                append(hex[code and 0x0f])
            }
        }
    }
}
