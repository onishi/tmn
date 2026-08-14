package com.tmn.broadcaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.DataChannel

/**
 * 常時起動のForeground Service。
 * シグナリングWebSocketは常時維持し、視聴者接続(viewer-joined)をトリガーに
 * カメラ取得・PeerConnection確立・配信を開始する(オンデマンド配信)。
 * 視聴者切断(viewer-left)で配信パイプラインを停止する。
 */
class StreamingService : Service(), SignalingClient.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var signalingClient: SignalingClient
    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_text_idle)))

        eglBase = EglBase.create()
        initPeerConnectionFactory()

        signalingClient = SignalingClient(signalingUrl(), this)
        signalingClient.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopStreaming()
        signalingClient.close()
        peerConnectionFactory.dispose()
        eglBase.release()
        super.onDestroy()
    }

    private fun signalingUrl(): String {
        val base = "${Config.SIGNALING_URL}/room/${Config.ROOM_TOKEN}?role=broadcaster"
        return if (Config.ACCESS_PASSWORD.isNotEmpty()) {
            "$base&password=${Uri.encode(Config.ACCESS_PASSWORD)}"
        } else {
            base
        }
    }

    private fun initPeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    // --- SignalingClient.Listener ---

    override fun onOpen() {
        Log.i(TAG, "signaling connected")
    }

    override fun onMessage(text: String) {
        val message = JSONObject(text)
        scope.launch {
            when (message.optString("type")) {
                "viewer-joined" -> startStreaming()
                "viewer-left" -> stopStreaming()
                "answer" -> onRemoteAnswer(message.getString("sdp"))
                "ice-candidate" -> onRemoteIceCandidate(message.getJSONObject("candidate"))
            }
        }
    }

    override fun onClosed() {
        Log.w(TAG, "signaling closed")
    }

    override fun onFailure(t: Throwable) {
        Log.e(TAG, "signaling error", t)
    }

    // --- streaming pipeline ---

    private fun startStreaming() {
        // 視聴側が再接続してきた場合も含め、常に新しいPeerConnectionで配信をやり直す
        if (peerConnection != null) stopStreaming()

        updateNotification(getString(R.string.notification_text_streaming))

        val capturer = createCameraCapturer()
        if (capturer == null) {
            Log.e(TAG, "no camera available")
            return
        }
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        val source = peerConnectionFactory.createVideoSource(false)
        videoSource = source
        capturer.initialize(helper, applicationContext, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        val videoTrack = peerConnectionFactory.createVideoTrack("tmn-video", source)

        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        )

        val connection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) = sendIceCandidate(candidate)
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }) ?: run {
            Log.e(TAG, "failed to create PeerConnection")
            return
        }
        peerConnection = connection

        connection.addTrack(videoTrack, listOf("tmn-stream"))

        connection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription?) {
                val offer = description ?: return
                connection.setLocalDescription(SdpObserverAdapter(), offer)
                sendSdp("offer", offer.description)
            }
        }, MediaConstraints())
    }

    private fun stopStreaming() {
        videoCapturer?.let {
            try {
                it.stopCapture()
            } catch (e: InterruptedException) {
                Log.w(TAG, "stopCapture interrupted", e)
            }
            it.dispose()
        }
        videoCapturer = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        peerConnection?.close()
        peerConnection = null

        updateNotification(getString(R.string.notification_text_idle))
    }

    private fun onRemoteAnswer(sdp: String) {
        peerConnection?.setRemoteDescription(
            SdpObserverAdapter(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun onRemoteIceCandidate(candidateJson: JSONObject) {
        val candidate = IceCandidate(
            candidateJson.getString("sdpMid"),
            candidateJson.getInt("sdpMLineIndex"),
            candidateJson.getString("candidate")
        )
        peerConnection?.addIceCandidate(candidate)
    }

    private fun sendSdp(type: String, sdp: String) {
        val json = JSONObject().apply {
            put("type", type)
            put("sdp", sdp)
        }
        signalingClient.send(json.toString())
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("type", "ice-candidate")
            put("candidate", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }
        signalingClient.send(json.toString())
    }

    /** 背面カメラを優先して選択する */
    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(applicationContext)
        val deviceNames = enumerator.deviceNames

        for (name in deviceNames) {
            if (enumerator.isBackFacing(name)) {
                enumerator.createCapturer(name, null)?.let { return it }
            }
        }
        for (name in deviceNames) {
            enumerator.createCapturer(name, null)?.let { return it }
        }
        return null
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "tmn_streaming"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "StreamingService"
        private const val NOTIFICATION_ID = 1
    }
}
