package com.oneturn.transfer.identity

import com.oneturn.transfer.signaling.ClaimDeviceRequest
import com.oneturn.transfer.signaling.RegisterDeviceRequest
import com.oneturn.transfer.signaling.SignalingClient
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
        val dto = signaling.claimDevice(
            ClaimDeviceRequest(
                pairingCode = pairingCode,
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
    }

    private fun refreshTrustedDevices() {
        _trustedDevices.value = identityRepository.listTrustedDevices()
    }

    private fun TrustedDeviceDto.toTrustedDevice(): TrustedDevice =
        TrustedDevice(deviceId, publicKey, displayName)
}

data class PairingRegistration(
    val pairingCode: String,
    val expiresAt: Long,
)
