package org.wagaya.tmn.caster

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * ローカルプレビュー表示のために、StreamingServiceが保持するEGLコンテキストと
 * 現在配信中の共有videoTrackをMainActivity(画面表示)へ橋渡しする。
 * 視聴者がいない間はtrackがnullになる(=プレビューにも何も映らない)。
 */
object CameraPreviewBridge {
    private val _eglBaseContext = MutableStateFlow<EglBase.Context?>(null)
    val eglBaseContext: StateFlow<EglBase.Context?> = _eglBaseContext

    private val _track = MutableStateFlow<VideoTrack?>(null)
    val track: StateFlow<VideoTrack?> = _track

    fun setEglBaseContext(context: EglBase.Context?) {
        _eglBaseContext.value = context
    }

    fun setTrack(track: VideoTrack?) {
        _track.value = track
    }
}
