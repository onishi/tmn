package org.wagaya.tmn.caster

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * StreamingServiceの現在の状態をMainActivity(画面表示)へ橋渡しするための共有ホルダー。
 * 同一プロセス内(Bound Serviceではない)で完結するため、シンプルにStateFlowで公開する。
 */
object StreamingStatus {
    /**
     * 配信の手動制御モード。
     * AUTO: 視聴者が来た時だけ自動でカメラ起動・配信する(既定の挙動)
     * ON: 視聴者の有無に関わらず常時カメラを起動しておく
     * OFF: 視聴者が来ても配信しない(カメラ・見回り検知も含め一切使わない)
     */
    enum class StreamMode { AUTO, ON, OFF }

    data class ViewerState(val viewerId: String, val iceState: String, val browserName: String = "?")

    data class State(
        val signalingConnected: Boolean = false,
        val viewers: List<ViewerState> = emptyList(),
        val detectionText: String = "-",
        val streamMode: StreamMode = StreamMode.AUTO,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }
}
