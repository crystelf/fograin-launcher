package com.fograin.launcher.settings

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** `appearance.theme` in the shared `launcher.toml` (owned by the Rust core). */
enum class ThemeMode(val wireValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromWire(value: String?): ThemeMode =
            entries.firstOrNull { it.wireValue == value } ?: SYSTEM
    }
}

/**
 * Decodes `theme` from the FFI snapshot. Unknown values (a newer core or a
 * hand-edited launcher.toml) fall back to [ThemeMode.SYSTEM] instead of failing
 * the whole snapshot.
 */
object ThemeModeSerializer : KSerializer<ThemeMode> {
    override val descriptor = PrimitiveSerialDescriptor("ThemeMode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ThemeMode =
        ThemeMode.fromWire(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: ThemeMode) =
        encoder.encodeString(value.wireValue)
}
