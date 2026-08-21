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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    // 視聴者ごと(viewerId)に個別のPeerConnectionを保持する。カメラ・映像トラックは
    // 全視聴者で共有し、最初の視聴者接続時に起動、最後の視聴者切断時に停止する。
    private val peerConnections = mutableMapOf<String, PeerConnection>()

    // 視聴者ごとに、一定時間内にICE接続が確立しなければ自動的に切断するタイムアウト監視ジョブ。
    // ブラウザタブが正常なclose通知を送らずに閉じた場合など、接続が"new"のまま残り続けるのを防ぐ
    private val viewerTimeoutJobs = mutableMapOf<String, Job>()
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var catPersonDetector: CatPersonDetector? = null
    private var lastDetectionAtMs = 0L

    // 手動配信モード(AUTO/ON/OFF)。既定はAUTO(視聴者が来た時だけ自動配信)
    private var streamMode = StreamingStatus.StreamMode.AUTO

    // 待機中(無配信)でも、たまにカメラを短時間だけ起動して検知を行うためのループとリソース
    private var idleDetectionJob: Job? = null
    private var idleVideoCapturer: VideoCapturer? = null
    private var idleSurfaceTextureHelper: SurfaceTextureHelper? = null
    private var idleVideoSource: VideoSource? = null
    private var idleVideoTrack: VideoTrack? = null

    /**
     * 配信中・待機中どちらの経路からも共有して使う検知シンク。間引いたフレームだけ
     * 動物・人検知に回す(常時推論はしない)。結果は [onDetectionResult] でその時点の
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
        CameraPreviewBridge.setEglBaseContext(eglBase.eglBaseContext)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DETECT_NOW -> triggerImmediateDetection()
            ACTION_SET_STREAM_MODE -> {
                val modeName = intent.getStringExtra(EXTRA_STREAM_MODE)
                StreamingStatus.StreamMode.entries.find { it.name == modeName }?.let { applyStreamMode(it) }
            }
        }
        return START_STICKY
    }

    /**
     * 画面の「今すぐ検知」ボタンから呼ばれる。共有カメラが既に起動中(視聴者あり、または
     * ONモード)なら次に届くフレームですぐ検知させるためスロットルをリセットするだけ、
     * そうでなければ見回りサイクルを今すぐ1回実行する。
     */
    private fun triggerImmediateDetection() {
        if (videoTrack != null) {
            lastDetectionAtMs = 0L
        } else {
            scope.launch { runIdleDetectionCycle() }
        }
    }

    /**
     * 画面のAUTO/ON/OFFトグルから呼ばれる、手動配信モードの切り替え。
     * OFF: 接続中の視聴者を全員切断し、カメラ(見回り検知含め)を一切使わない
     * ON: 視聴者の有無に関わらず常時カメラを起動しておく
     * AUTO: 通常の視聴者駆動の自動配信に戻す(ONから戻った場合、視聴者がいなければカメラを止める)
     */
    private fun applyStreamMode(mode: StreamingStatus.StreamMode) {
        if (streamMode == mode) return
        val previousMode = streamMode
        streamMode = mode
        StreamingStatus.update { it.copy(streamMode = mode) }

        when (mode) {
            StreamingStatus.StreamMode.OFF -> {
                peerConnections.keys.toList().forEach { stopStreamingForViewer(it) }
                stopIdleDetectionCycle()
                stopSharedCapture()
                updateNotification(getString(R.string.notification_text_off))
            }
            StreamingStatus.StreamMode.ON -> {
                if (videoTrack == null) {
                    stopIdleDetectionCycle()
                    if (startSharedCapture()) {
                        updateNotification(getString(R.string.notification_text_streaming))
                    }
                }
            }
            StreamingStatus.StreamMode.AUTO -> {
                if (previousMode == StreamingStatus.StreamMode.ON && peerConnections.isEmpty()) {
                    stopSharedCapture()
                    updateNotification(getString(R.string.notification_text_idle))
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        idleDetectionJob?.cancel()
        stopIdleDetectionCycle()
        viewerTimeoutJobs.values.forEach { it.cancel() }
        viewerTimeoutJobs.clear()
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        stopSharedCapture()
        CameraPreviewBridge.setEglBaseContext(null)
        catPersonDetector?.close()
        signalingClient.close()
        peerConnectionFactory.dispose()
        eglBase.release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 待機中に短時間だけカメラを起動し、検知シンクにフレームを流す1サイクル分。
     * 視聴者接続([startStreaming])が同時に起きた場合は、そちらが[stopIdleDetectionCycle]で
     * このサイクルのカメラを横取りして即座に手放す(同一カメラを二重に開けないため)。
     */
    private suspend fun runIdleDetectionCycle() {
        if (streamMode == StreamingStatus.StreamMode.OFF) return // 配信OFF中はカメラを一切使わない
        if (peerConnections.isNotEmpty()) return // 配信中は専用の経路で既に検知しているのでスキップ
        if (videoTrack != null) return // ONモード等で共有カメラが既に起動中なら、そちらで既に検知している
        if (catPersonDetector == null) return
        if (idleVideoCapturer != null) return // 既に見回り中(定期実行と「今すぐ検知」の重複を防ぐ)

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

        val detectionSummary = when {
            result.hasAnimal && result.hasPerson -> "動物と人を検知"
            result.hasAnimal -> "動物を検知"
            result.hasPerson -> "人を検知"
            else -> "検知なし"
        }
        StreamingStatus.update { it.copy(detectionText = detectionSummary) }

        val text = if (videoTrack != null) {
            when {
                result.hasAnimal && result.hasPerson -> getString(R.string.notification_text_streaming_animal_and_person)
                result.hasAnimal -> getString(R.string.notification_text_streaming_animal)
                result.hasPerson -> getString(R.string.notification_text_streaming_person)
                else -> getString(R.string.notification_text_streaming)
            }
        } else {
            // 配信中でなければ、待機中の見回りサイクルから来た結果として扱う
            when {
                result.hasAnimal && result.hasPerson -> getString(R.string.notification_text_idle_animal_and_person)
                result.hasAnimal -> getString(R.string.notification_text_idle_animal)
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

    private fun turnCredentialsUrl(): String {
        val httpBase = Config.SIGNALING_URL
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
        val base = "$httpBase/turn-credentials"
        return if (Config.ACCESS_PASSWORD.isNotEmpty()) {
            "$base?password=${Uri.encode(Config.ACCESS_PASSWORD)}"
        } else {
            base
        }
    }

    /**
     * シグナリングWorker経由でCloudflare CallsのTURN認証情報を取得する。
     * 同じWi-Fiルーター配下同士でも、ルーターがNATヘアピンに対応していないとSTUNのみでは
     * P2P接続が確立できないことがあるため、フォールバックとして使う。
     * 取得できなくても配信自体は継続させる(STUNのみで接続を試みる)。
     */
    private suspend fun fetchTurnIceServers(): List<PeerConnection.IceServer> {
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(turnCredentialsUrl()).build()
                OkHttpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val iceServers = JSONObject(body).optJSONObject("iceServers")
                        ?: return@withContext emptyList()
                    val urlsJson = iceServers.getJSONArray("urls")
                    val urls = (0 until urlsJson.length()).map { urlsJson.getString(it) }
                    listOf(
                        PeerConnection.IceServer.builder(urls)
                            .setUsername(iceServers.getString("username"))
                            .setPassword(iceServers.getString("credential"))
                            .createIceServer()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to fetch TURN credentials; falling back to STUN only", e)
            emptyList()
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
        StreamingStatus.update { it.copy(signalingConnected = true) }
    }

    override fun onMessage(text: String) {
        scope.launch {
            try {
                val message = JSONObject(text)
                // viewerIdの無いメッセージ(古いバージョンのViewerが接続してきた場合など)は
                // 相手が特定できないため無視する。1つの不正なメッセージでサービス全体を
                // 落とさないよう、シグナリング経由の外部入力はここで防御的に扱う
                val viewerId = message.optString("viewerId").ifEmpty { null }
                when (message.optString("type")) {
                    "viewer-joined" -> viewerId?.let {
                        val browserName = message.optString("browserName").ifEmpty { "?" }
                        // OFF中は視聴者が来ても配信しない(offerを送らないだけで、
                        // Viewer側は「配信アプリからの応答を待っています」の表示のまま待機する)
                        if (streamMode != StreamingStatus.StreamMode.OFF) startStreamingForViewer(it, browserName)
                    }
                    "viewer-left" -> viewerId?.let { stopStreamingForViewer(it) }
                    "answer" -> viewerId?.let { onRemoteAnswer(it, message.getString("sdp")) }
                    "ice-candidate" -> viewerId?.let {
                        onRemoteIceCandidate(it, message.getJSONObject("candidate"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to handle signaling message", e)
            }
        }
    }

    override fun onClosed() {
        Log.w(TAG, "signaling closed")
        StreamingStatus.update { it.copy(signalingConnected = false) }
    }

    override fun onFailure(t: Throwable) {
        Log.e(TAG, "signaling error", t)
        StreamingStatus.update { it.copy(signalingConnected = false) }
    }

    // --- streaming pipeline ---

    /** カメラ映像の取得を開始し、共有の [videoTrack] を用意する。全視聴者共通で1つだけ起動する */
    private fun startSharedCapture(): Boolean {
        val capturer = createCameraCapturer()
        if (capturer == null) {
            Log.e(TAG, "no camera available")
            return false
        }
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        val source = peerConnectionFactory.createVideoSource(false)
        videoSource = source
        capturer.initialize(helper, applicationContext, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        val track = peerConnectionFactory.createVideoTrack("tmn-video", source)
        videoTrack = track
        track.addSink(detectionSink)
        CameraPreviewBridge.setTrack(track)
        return true
    }

    private fun stopSharedCapture() {
        videoTrack?.removeSink(detectionSink)
        videoTrack = null
        CameraPreviewBridge.setTrack(null)

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
    }

    /**
     * 視聴者(viewerId)ごとに個別のPeerConnectionを確立してofferを送る。
     * カメラ・映像トラックは全視聴者で共有し、最初の視聴者接続時にのみ起動する。
     */
    private suspend fun startStreamingForViewer(viewerId: String, browserName: String) {
        // 待機中の見回り検知サイクルがカメラを使用中であれば、即座に横取りして手放す
        // (同一カメラをCamera2で二重に開くことはできないため)
        stopIdleDetectionCycle()

        // 同じ視聴者からの再接続の場合、古いPeerConnectionが残っていれば張り直す
        peerConnections.remove(viewerId)?.close()
        viewerTimeoutJobs.remove(viewerId)?.cancel()
        StreamingStatus.update { s -> s.copy(viewers = s.viewers.filter { it.viewerId != viewerId }) }

        // ONモードなどで既に共有カメラが起動中の場合は、そのまま使い回す(二重に開かない)
        if (videoTrack == null) {
            if (!startSharedCapture()) return
        }
        updateNotification(getString(R.string.notification_text_streaming))
        val track = videoTrack ?: return

        val turnIceServers = fetchTurnIceServers()

        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()) + turnIceServers
        )

        val connection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) = sendIceCandidate(viewerId, candidate)
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "viewer=$viewerId iceConnectionState=$state")
                // Observerのコールバックスレッドから直接、他のメソッドと共有しているミュータブルな
                // 状態(peerConnections/viewerTimeoutJobs)を触るのは避け、scope(メインスレッド)経由に揃える
                scope.launch {
                    StreamingStatus.update { s ->
                        s.copy(viewers = s.viewers.map {
                            if (it.viewerId == viewerId) it.copy(iceState = state.toString()) else it
                        })
                    }
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            viewerTimeoutJobs.remove(viewerId)?.cancel()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.w(TAG, "viewer=$viewerId ICE connection failed; dropping")
                            stopStreamingForViewer(viewerId)
                        }
                        else -> {}
                    }
                }
            }
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
        peerConnections[viewerId] = connection
        StreamingStatus.update { s ->
            s.copy(viewers = s.viewers + StreamingStatus.ViewerState(viewerId, "new", browserName))
        }

        // 一定時間内にICE接続が確立しなければ、この視聴者を諦めて切断する
        // (ブラウザタブが正常なclose通知を送らずに閉じた場合などに"new"のまま残り続けるのを防ぐ)
        viewerTimeoutJobs[viewerId] = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            val state = connection.iceConnectionState()
            if (state != PeerConnection.IceConnectionState.CONNECTED &&
                state != PeerConnection.IceConnectionState.COMPLETED
            ) {
                Log.w(TAG, "viewer=$viewerId ICE connection timed out (state=$state); dropping")
                stopStreamingForViewer(viewerId)
            }
        }

        connection.addTrack(track, listOf("tmn-stream"))

        connection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription?) {
                val offer = description ?: return
                connection.setLocalDescription(SdpObserverAdapter(), offer)
                sendSdp("offer", offer.description, viewerId)
            }
        }, MediaConstraints())
    }

    /** 指定の視聴者のPeerConnectionを閉じる。全視聴者がいなくなった場合のみカメラも止める */
    private fun stopStreamingForViewer(viewerId: String) {
        peerConnections.remove(viewerId)?.close()
        viewerTimeoutJobs.remove(viewerId)?.cancel()
        StreamingStatus.update { s -> s.copy(viewers = s.viewers.filter { it.viewerId != viewerId }) }

        // ONモードでは視聴者がいなくなってもカメラを起動し続ける
        if (peerConnections.isEmpty() && streamMode != StreamingStatus.StreamMode.ON) {
            stopSharedCapture()
            updateNotification(getString(R.string.notification_text_idle))
        }
    }

    private fun onRemoteAnswer(viewerId: String, sdp: String) {
        peerConnections[viewerId]?.setRemoteDescription(
            SdpObserverAdapter(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun onRemoteIceCandidate(viewerId: String, candidateJson: JSONObject) {
        val candidate = IceCandidate(
            candidateJson.getString("sdpMid"),
            candidateJson.getInt("sdpMLineIndex"),
            candidateJson.getString("candidate")
        )
        peerConnections[viewerId]?.addIceCandidate(candidate)
    }

    private fun sendSdp(type: String, sdp: String, viewerId: String) {
        val json = JSONObject().apply {
            put("type", type)
            put("sdp", sdp)
            put("viewerId", viewerId)
        }
        signalingClient.send(json.toString())
    }

    private fun sendIceCandidate(viewerId: String, candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("type", "ice-candidate")
            put("viewerId", viewerId)
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
            put("hasAnimal", result.hasAnimal)
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

        // MainActivityの「今すぐ検知」ボタンから送られるIntentのaction
        const val ACTION_DETECT_NOW = "org.wagaya.tmn.caster.ACTION_DETECT_NOW"

        // MainActivityのAUTO/ON/OFFトグルから送られるIntentのaction・extraキー
        const val ACTION_SET_STREAM_MODE = "org.wagaya.tmn.caster.ACTION_SET_STREAM_MODE"
        const val EXTRA_STREAM_MODE = "stream_mode"

        // この時間内にICE接続がCONNECTED/COMPLETEDへ進まなければ、その視聴者を諦めて切断する
        private const val CONNECTION_TIMEOUT_MS = 20000L

        // 動物・人検知の実行間隔。毎フレーム推論するとバッテリー消費が大きいため間引く
        private const val DETECTION_INTERVAL_MS = 30000L

        // 待機中(無配信)でもカメラを起動して見回る間隔。常時電源に接続して運用する前提の
        // ため、バッテリー消費よりカメラ開閉のオーバーヘッド・発熱がより重要な制約になる。
        // 1分間隔(稼働率約8%)は見逃しにくさと負荷のバランスとして現実的な値
        private const val IDLE_DETECTION_INTERVAL_MS = 60 * 1000L

        // 待機中の見回りでカメラを起動しておく時間。古いスマホではカメラの起動(Camera2の
        // セッション確立)自体に1秒前後かかることがあるため、最低1フレームは確実に
        // 検知シンクへ届くよう、なるべく短くしつつも余裕を持たせる
        private const val IDLE_DETECTION_CAPTURE_DURATION_MS = 5000L
    }
}
