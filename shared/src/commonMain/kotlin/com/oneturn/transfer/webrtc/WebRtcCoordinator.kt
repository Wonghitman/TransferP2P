package com.oneturn.transfer.webrtc

import com.oneturn.transfer.signaling.IceServerDto
import com.oneturn.transfer.signaling.SignalingClient
import com.oneturn.transfer.signaling.SignalingMessage
import com.oneturn.transfer.signaling.SignalingRole
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class WebRtcCoordinator(
    private val scope: CoroutineScope,
    private val signaling: SignalingClient,
    private val role: SignalingRole,
    private val iceServers: List<IceServerConfig>,
    private val onStatus: (String) -> Unit = {},
) {
    private var session: WebRtcSession? = null
    private var jobs = mutableListOf<Job>()

    val activeSession: WebRtcSession?
        get() = session

    suspend fun start() {
        val peerRole = if (role == SignalingRole.Sender) PeerRole.Initiator else PeerRole.Responder
        val webRtcSession = WebRtcSession(scope, peerRole, iceServers)
        session = webRtcSession

        jobs += scope.launch {
            webRtcSession.localIceCandidates.collect { candidate ->
                val adjusted = boostIpv6CandidatePriority(candidate.candidate)
                signaling.send(
                    SignalingMessage.IceCandidate(
                        from = signaling.peerId,
                        candidate = adjusted,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    ),
                )
            }
        }

        jobs += scope.launch {
            signaling.incoming.filterIsInstance<SignalingMessage.IceCandidate>().collect { message ->
                if (message.from == signaling.peerId) return@collect
                webRtcSession.addRemoteIceCandidate(
                    IceCandidate(
                        sdpMid = message.sdpMid.orEmpty(),
                        sdpMLineIndex = message.sdpMLineIndex ?: 0,
                        candidate = message.candidate,
                    ),
                )
            }
        }

        onStatus("交换 SDP...")
        if (peerRole == PeerRole.Initiator) {
            val offer = webRtcSession.createOffer()
            signaling.send(
                SignalingMessage.Offer(
                    from = signaling.peerId,
                    sdp = offer.sdp,
                ),
            )
            val answerMessage = withTimeout(SDP_TIMEOUT_MS) {
                signaling.incoming
                    .filterIsInstance<SignalingMessage.Answer>()
                    .first { it.from != signaling.peerId }
            }
            webRtcSession.setRemoteAnswer(
                SessionDescription(SessionDescriptionType.Answer, answerMessage.sdp),
            )
        } else {
            val offerMessage = withTimeout(SDP_TIMEOUT_MS) {
                signaling.incoming
                    .filterIsInstance<SignalingMessage.Offer>()
                    .first { it.from != signaling.peerId }
            }
            val answer = webRtcSession.createAnswer(
                SessionDescription(SessionDescriptionType.Offer, offerMessage.sdp),
            )
            signaling.send(
                SignalingMessage.Answer(
                    from = signaling.peerId,
                    sdp = answer.sdp,
                ),
            )
        }

        onStatus("等待 P2P 通道就绪...")
        webRtcSession.waitForDataChannelOpen()
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        session?.close()
        session = null
    }

    companion object {
        private const val SDP_TIMEOUT_MS = 45_000L

        fun defaultIceServers(turn: List<IceServerDto> = emptyList()): List<IceServerConfig> =
            buildList {
                add(IceServerConfig(urls = listOf("stun:stun.l.google.com:19302")))
                add(IceServerConfig(urls = listOf("stun:stun.cloudflare.com:3478")))
                addAll(turn.map { it.toConfig() })
            }
    }
}
