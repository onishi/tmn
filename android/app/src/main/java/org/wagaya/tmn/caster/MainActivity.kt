package org.wagaya.tmn.caster

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var previewRenderer: SurfaceViewRenderer? = null
    private var previewInitialized = false
    private var attachedPreviewTrack: VideoTrack? = null

    private lateinit var streamModeToggle: MaterialButtonToggleGroup

    // updateStatusViews()からトグルの選択状態をプログラム的に合わせる際、
    // それ自体がリスナーを発火させてIntent送信ループにならないようにするガード
    private var suppressModeCallback = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // カメラ権限さえ得られれば配信自体は可能。通知権限(Android 13+)は
            // 拒否されても常駐通知が出ないだけでサービス自体は動作する
            if (result[Manifest.permission.CAMERA] == true) {
                startStreamingService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        val missingPermissions = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startStreamingService()
        } else {
            requestPermissions.launch(missingPermissions.toTypedArray())
        }

        requestIgnoreBatteryOptimizations()

        activityScope.launch {
            StreamingStatus.state.collect { state -> updateStatusViews(state) }
        }

        // EGLコンテキストの用意(Service起動)とvideoTrackの有無(視聴者の有無)は
        // 別々のタイミングで変化するため、両方の最新状態を見て毎回同期させる
        activityScope.launch {
            combine(CameraPreviewBridge.eglBaseContext, CameraPreviewBridge.track) { ctx, track -> ctx to track }
                .collect { (ctx, track) -> updatePreview(ctx, track) }
        }
    }

    /**
     * 画面回転時に呼ばれる。マニフェストのconfigChangesでActivityの再生成は抑制しているため
     * (SurfaceViewRendererの再生成によるプレビューのちらつきを避けるため)、
     * layout/とlayout-land/を切り替えるsetContentView()もAndroidが自動では呼んでくれない。
     * ここで手動にcontentViewを作り直し、状態を新しいViewへ反映し直す。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        previewRenderer?.let { renderer ->
            attachedPreviewTrack?.removeSink(renderer)
            if (previewInitialized) renderer.release()
        }
        previewRenderer = null
        previewInitialized = false
        attachedPreviewTrack = null

        setContentView(R.layout.activity_main)
        bindViews()
        updateStatusViews(StreamingStatus.state.value)
        updatePreview(CameraPreviewBridge.eglBaseContext.value, CameraPreviewBridge.track.value)
    }

    private fun bindViews() {
        findViewById<TextView>(R.id.room_info_text).text =
            "接続先: ${Config.SIGNALING_URL}\nルームトークン: ${Config.ROOM_TOKEN}"

        previewRenderer = findViewById(R.id.preview_renderer)

        findViewById<Button>(R.id.detect_now_button).setOnClickListener {
            val intent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_DETECT_NOW
            }
            ContextCompat.startForegroundService(this, intent)
        }

        streamModeToggle = findViewById(R.id.stream_mode_toggle)
        streamModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (suppressModeCallback || !isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.mode_on_button -> StreamingStatus.StreamMode.ON
                R.id.mode_off_button -> StreamingStatus.StreamMode.OFF
                else -> StreamingStatus.StreamMode.AUTO
            }
            val intent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_SET_STREAM_MODE
                putExtra(StreamingService.EXTRA_STREAM_MODE, mode.name)
            }
            ContextCompat.startForegroundService(this, intent)
        }

        findViewById<Button>(R.id.quit_button).setOnClickListener {
            // 配信サービスを止めてから(カメラ・PeerConnection・通知を後片付け)アプリを終了する
            stopService(Intent(this, StreamingService::class.java))
            finishAndRemoveTask()
        }
    }

    private fun updatePreview(eglBaseContext: org.webrtc.EglBase.Context?, track: VideoTrack?) {
        val renderer = previewRenderer ?: return

        if (eglBaseContext != null && !previewInitialized) {
            renderer.init(eglBaseContext, null)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            renderer.setMirror(false)
            previewInitialized = true
        }
        if (!previewInitialized) return

        if (attachedPreviewTrack !== track) {
            attachedPreviewTrack?.removeSink(renderer)
            attachedPreviewTrack = track
            if (track != null) {
                track.addSink(renderer)
            } else {
                // 配信停止時、最後のフレームが残ったままに見えて紛らわしいのでクリアする
                renderer.clearImage()
            }
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        previewRenderer?.let { renderer ->
            attachedPreviewTrack?.removeSink(renderer)
            if (previewInitialized) renderer.release()
        }
        super.onDestroy()
    }

    private fun updateStatusViews(state: StreamingStatus.State) {
        findViewById<TextView>(R.id.signaling_status_text).text =
            if (state.signalingConnected) "シグナリング: 接続中" else "シグナリング: 切断"

        val dotColorRes = if (state.signalingConnected) R.color.tmn_success else R.color.tmn_text_secondary
        findViewById<android.view.View>(R.id.signaling_status_dot).backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, dotColorRes))

        findViewById<TextView>(R.id.detection_text).text = "直近の検知: ${state.detectionText}"

        val targetButtonId = when (state.streamMode) {
            StreamingStatus.StreamMode.ON -> R.id.mode_on_button
            StreamingStatus.StreamMode.OFF -> R.id.mode_off_button
            StreamingStatus.StreamMode.AUTO -> R.id.mode_auto_button
        }
        if (streamModeToggle.checkedButtonId != targetButtonId) {
            suppressModeCallback = true
            streamModeToggle.check(targetButtonId)
            suppressModeCallback = false
        }
        updateModeButtonAppearance(targetButtonId)
        findViewById<TextView>(R.id.stream_mode_description_text).text = when (state.streamMode) {
            StreamingStatus.StreamMode.AUTO -> "視聴者が来た時だけ自動でカメラを起動します"
            StreamingStatus.StreamMode.ON -> "視聴者の有無に関わらず常時カメラを起動しています"
            StreamingStatus.StreamMode.OFF -> "配信を停止しています(視聴者が来ても配信しません)"
        }

        findViewById<TextView>(R.id.viewers_list_text).text = if (state.viewers.isEmpty()) {
            "視聴者なし(待機中)"
        } else {
            state.viewers.joinToString("\n") { "${it.viewerId.take(8)}…  ${it.browserName}  ${it.iceState}" }
        }
    }

    /**
     * 選択中のボタンだけ塗りつぶし(AUTOはオレンジ、ONは緑、OFFは赤)、他はグレーの地味な見た目にする。
     * XMLのColorStateListセレクタ経由だと環境によってはbackgroundTintが正しく解決されない
     * ことがあったため、確実に反映されるようKotlin側で直接色を設定している。
     */
    private fun updateModeButtonAppearance(selectedButtonId: Int) {
        val selectedColors = mapOf(
            R.id.mode_auto_button to R.color.tmn_accent,
            R.id.mode_on_button to R.color.tmn_success,
            R.id.mode_off_button to R.color.tmn_danger,
        )
        for ((buttonId, selectedColorRes) in selectedColors) {
            val button = findViewById<MaterialButton>(buttonId)
            if (buttonId == selectedButtonId) {
                button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, selectedColorRes))
                button.setTextColor(Color.WHITE)
            } else {
                button.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.tmn_surface_bright))
                button.setTextColor(ContextCompat.getColor(this, R.color.tmn_text_primary))
            }
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions
    }

    private fun startStreamingService() {
        val intent = Intent(this, StreamingService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}
