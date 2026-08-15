package org.wagaya.tmn.caster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
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
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var catPersonDetector: CatPersonDetector? = null
    private var lastDetectionAtMs = 0L

    // 待機中(無配信)でも、たまにカメラを短時間だけ起動して検知を行うためのループとリソース
    private var idleDetectionJob: Job? = null
    private var idleVideoCapturer: VideoCapturer? = null
    private var idleSurfaceTextureHelper: SurfaceTextureHelper? = null
    private var idleVideoSource: VideoSource? = null
    private var idleVideoTrack: VideoTrack? = null

    /**
     * 配信中・待機中どちらの経路からも共有して使う検知シンク。間引いたフレームだけ
     * 猫・人検知に回す(常時推論はしない)。結果は [onDetectionResult] でその時点の
     * 配信状態(peerConnectionの有無)に応じて振り分けられる。
     */
    private val detectionSink = VideoSink { frame ->
        val detector = catPersonDetector
        val now = SystemClock.elapsedRealtime()
        if (detector == null || now - lastDetectionAtMs < DETECTION_INTERVAL_MS) {
            return@VideoSink // retainしていないため、何もしなくてもWebRTC側が解放する
        }
        lastDetectionAtMs = now
        frame.retain()
        detector.detectFrameAsync(frame, now)
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_text_idle)))

        eglBase = EglBase.create()
        initPeerConnectionFactory()
        catPersonDetector = createCatPersonDetector()

        signalingClient = SignalingClient(signalingUrl(), this)
        signalingClient.connect()

        idleDetectionJob = scope.launch {
            while (isActive) {
                delay(IDLE_DETECTION_INTERVAL_MS)
                runIdleDetectionCycle()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        idleDetectionJob?.cancel()
        stopIdleDetectionCycle()
        stopStreaming()
        catPersonDetector?.close()
        signalingClient.close()
        peerConnectionFactory.dispose()
        eglBase.release()
        super.onDestroy()
    }

    /**
     * 待機中に短時間だけカメラを起動し、検知シンクにフレームを流す1サイクル分。
     * 視聴者接続([startStreaming])が同時に起きた場合は、そちらが[stopIdleDetectionCycle]で
     * このサイクルのカメラを横取りして即座に手放す(同一カメラを二重に開けないため)。
     */
    private suspend fun runIdleDetectionCycle() {
        if (peerConnection != null) return // 配信中は専用の経路で既に検知しているのでスキップ
        if (catPersonDetector == null) return

        val capturer = createCameraCapturer() ?: return
        idleVideoCapturer = capturer

        val helper = SurfaceTextureHelper.create("IdleDetectThread", eglBase.eglBaseContext)
        idleSurfaceTextureHelper = helper

        val source = peerConnectionFactory.createVideoSource(false)
        idleVideoSource = source

        val track = peerConnectionFactory.createVideoTrack("tmn-idle-detect", source)
        idleVideoTrack = track
        track.addSink(detectionSink)

        capturer.initialize(helper, applicationContext, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        delay(IDLE_DETECTION_CAPTURE_DURATION_MS)

        // このdelay中にstartStreaming()に横取りされていた場合は、既にstopIdleDetectionCycle()で
        // 片付け済み・フィールドは別インスタンスかnullになっているため、二重解放しない
        if (idleVideoCapturer === capturer) {
            stopIdleDetectionCycle()
        }
    }

    private fun stopIdleDetectionCycle() {
        idleVideoTrack?.removeSink(detectionSink)
        idleVideoTrack = null

        idleVideoCapturer?.let {
            try {
                it.stopCapture()
            } catch (e: InterruptedException) {
                Log.w(TAG, "idle capture stop interrupted", e)
            }
            it.dispose()
        }
        idleVideoCapturer = null

        idleVideoSource?.dispose()
        idleVideoSource = null

        idleSurfaceTextureHelper?.dispose()
        idleSurfaceTextureHelper = null
    }

    private fun createCatPersonDetector(): CatPersonDetector? {
        return try {
            CatPersonDetector(applicationContext, ::onDetectionResult)
        } catch (e: Exception) {
            // モデル読み込み失敗時などでも、検知機能なしで配信自体は継続させる
            Log.e(TAG, "failed to initialize CatPersonDetector; continuing without detection", e)
            null
        }
    }

    private fun onDetectionResult(result: CatPersonDetector.CatPersonDetectionResult) {
        sendDetectionStatus(result)

        val text = if (peerConnection != null) {
            when {
                result.hasCat && result.hasPerson -> getString(R.string.notification_text_streaming_cat_and_person)
                result.hasCat -> getString(R.string.notification_text_streaming_cat)
                result.hasPerson -> getString(R.string.notification_text_streaming_person)
                else -> getString(R.string.notification_text_streaming)
            }
        } else {
            // 配信中でなければ、待機中の見回りサイクルから来た結果として扱う
            when {
                result.hasCat && result.hasPerson -> getString(R.string.notification_text_idle_cat_and_person)
                result.hasCat -> getString(R.string.notification_text_idle_cat)
                result.hasPerson -> getString(R.string.notification_text_idle_person)
                else -> getString(R.string.notification_text_idle)
            }
        }
        updateNotification(text)
    }

    private fun signalingUrl(): String {
        val base = "${Config.SIGNALING_URL}/room/${Config.ROOM_TOKEN}?role=caster"
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
        // 待機中の見回り検知サイクルがカメラを使用中であれば、即座に横取りして手放す
        // (同一カメラをCamera2で二重に開くことはできないため)
        stopIdleDetectionCycle()

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
        this.videoTrack = videoTrack
        videoTrack.addSink(detectionSink)

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
        videoTrack?.removeSink(detectionSink)
        videoTrack = null

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

    /**
     * 検知結果をシグナリングWorkerへ送る。Roomが直近の1件をキャッシュし、
     * Viewerが視聴を開始する前でも `GET /room/<token>/status` で確認できるようにする
     * (配信中であればViewer全員へそのまま中継もされる)。signalingClientは配信の
     * 有無に関わらず常時接続されているため、待機中の見回り結果もそのまま送れる。
     */
    private fun sendDetectionStatus(result: CatPersonDetector.CatPersonDetectionResult) {
        val json = JSONObject().apply {
            put("type", "detection-status")
            put("hasCat", result.hasCat)
            put("hasPerson", result.hasPerson)
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

        // 猫・人検知の実行間隔。毎フレーム推論するとバッテリー消費が大きいため間引く
        private const val DETECTION_INTERVAL_MS = 30000L

        // 待機中(無配信)でもカメラを起動して見回る間隔。WorkManagerの最短間隔(15分)に
        // 縛られる理由は無い(このサービス自体が常時起動しているため)が、バッテリー・発熱を
        // 抑えるため15分に1回程度に留める
        private const val IDLE_DETECTION_INTERVAL_MS = 15 * 60 * 1000L

        // 待機中の見回りでカメラを起動しておく時間。古いスマホではカメラの起動(Camera2の
        // セッション確立)自体に1秒前後かかることがあるため、最低1フレームは確実に
        // 検知シンクへ届くよう、なるべく短くしつつも余裕を持たせる
        private const val IDLE_DETECTION_CAPTURE_DURATION_MS = 5000L
    }
}
