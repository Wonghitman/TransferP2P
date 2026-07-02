package com.oneturn.transfer.identity

import com.oneturn.transfer.crypto.sha256Hex
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

@OptIn(ExperimentalEncodingApi::class)
class DeviceIdentityRepository(
    private val settings: Settings,
) {
    fun getOrCreate(): DeviceIdentity {
        val existingId = settings.getStringOrNull(KEY_DEVICE_ID)
        val existingKey = settings.getStringOrNull(KEY_PUBLIC_KEY)
        val existingName = settings.getStringOrNull(KEY_DISPLAY_NAME)
        if (existingId != null && existingKey != null && existingName != null) {
            return DeviceIdentity(existingId, existingKey, existingName)
        }
        val deviceId = generateDeviceId()
        val keyPairSeed = Random.nextBytes(32)
        val publicKey = Base64.encode(keyPairSeed)
        val displayName = "Device-${deviceId.takeLast(4)}"
        settings.putString(KEY_DEVICE_ID, deviceId)
        settings.putString(KEY_PUBLIC_KEY, publicKey)
        settings.putString(KEY_DISPLAY_NAME, displayName)
        return DeviceIdentity(deviceId, publicKey, displayName)
    }

    fun updateDisplayName(name: String) {
        settings.putString(KEY_DISPLAY_NAME, name)
    }

    fun listTrustedDevices(): List<TrustedDevice> {
        val raw = settings.getStringOrNull(KEY_TRUSTED_DEVICES).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size != 3) null else TrustedDevice(parts[0], parts[1], parts[2])
            }
            .toList()
    }

    fun addTrustedDevice(device: TrustedDevice) {
        val current = listTrustedDevices().toMutableList()
        current.removeAll { it.deviceId == device.deviceId }
        current += device
        settings.putString(
            KEY_TRUSTED_DEVICES,
            current.joinToString("\n") { "${it.deviceId}|${it.publicKey}|${it.displayName}" },
        )
    }

    fun fingerprint(publicKey: String): String =
        sha256Hex(publicKey).take(16)

    private fun generateDeviceId(): String =
        buildString {
            append("dev-")
            repeat(12) { append(Random.nextInt(16).toString(16)) }
        }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_TRUSTED_DEVICES = "trusted_devices"
    }
}

data class DeviceIdentity(
    val deviceId: String,
    val publicKey: String,
    val displayName: String,
)

data class TrustedDevice(
    val deviceId: String,
    val publicKey: String,
    val displayName: String,
)
