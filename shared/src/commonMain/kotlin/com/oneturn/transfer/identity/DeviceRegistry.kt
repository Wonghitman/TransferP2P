package com.oneturn.transfer.identity

import com.oneturn.transfer.signaling.ClaimDeviceRequest
import com.oneturn.transfer.signaling.RegisterDeviceRequest
import com.oneturn.transfer.signaling.SignalingClient
import com.oneturn.transfer.signaling.OnlineStatusRequest
import com.oneturn.transfer.signaling.OnlineStatusResponse
import com.oneturn.transfer.signaling.TrustedDeviceDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceRegistry(
    private val signaling: SignalingClient,
    private val identityRepository: DeviceIdentityRepository,
) {
    private val _trustedDevices = MutableStateFlow(identityRepository.listTrustedDevices())
    val trustedDevices: StateFlow<List<TrustedDevice>> = _trustedDevices.asStateFlow()

    private val _onlineStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val onlineStatus: StateFlow<Map<String, Boolean>> = _onlineStatus.asStateFlow()

    fun currentIdentity(): DeviceIdentity = identityRepository.getOrCreate()

    suspend fun startPairingRegistration(): PairingRegistration {
        val identity = identityRepository.getOrCreate()
        val response = signaling.registerDevice(
            RegisterDeviceRequest(
                deviceId = identity.deviceId,
                publicKey = identity.publicKey,
                displayName = identity.displayName,
            ),
        )
        return PairingRegistration(
            pairingCode = response.pairingCode,
            expiresAt = response.expiresAt,
        )
    }

    suspend fun claimPairingCode(
        pairingCode: String,
        displayName: String? = null,
    ): TrustedDevice {
        val identity = identityRepository.getOrCreate()
        val normalizedCode = pairingCode.trim().uppercase()
        val dto = signaling.claimDevice(
            ClaimDeviceRequest(
                pairingCode = normalizedCode,
                deviceId = identity.deviceId,
                publicKey = identity.publicKey,
                displayName = displayName ?: identity.displayName,
            ),
        )
        val trusted = dto.toTrustedDevice()
        identityRepository.addTrustedDevice(trusted)
        refreshTrustedDevices()
        return trusted
    }

    suspend fun refreshFromServer() {
        val identity = identityRepository.getOrCreate()
        val remote = signaling.listTrustedDevices(identity.deviceId)
        remote.forEach { dto ->
            identityRepository.addTrustedDevice(dto.toTrustedDevice())
        }
        refreshTrustedDevices()
        refreshOnlineStatus()
    }

    suspend fun refreshOnlineStatus() {
        val devices = identityRepository.listTrustedDevices()
        if (devices.isEmpty()) {
            _onlineStatus.value = emptyMap()
            return
        }
        val status = signaling.fetchOnlineStatus(devices.map { it.deviceId })
        _onlineStatus.value = status
        _trustedDevices.value = devices.map { device ->
            device.copy(online = status[device.deviceId] == true)
        }
    }

    fun updateDeviceOnline(deviceId: String, online: Boolean) {
        val current = _onlineStatus.value.toMutableMap()
        current[deviceId] = online
        _onlineStatus.value = current
        _trustedDevices.value = _trustedDevices.value.map { device ->
            if (device.deviceId == deviceId) device.copy(online = online) else device
        }
    }

    private fun refreshTrustedDevices() {
        val online = _onlineStatus.value
        _trustedDevices.value = identityRepository.listTrustedDevices().map { device ->
            device.copy(online = online[device.deviceId] == true)
        }
    }

    private fun TrustedDeviceDto.toTrustedDevice(): TrustedDevice =
        TrustedDevice(deviceId, publicKey, displayName, online)
}

data class PairingRegistration(
    val pairingCode: String,
    val expiresAt: Long,
)
