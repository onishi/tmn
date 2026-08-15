package com.tmn.caster

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** SdpObserverの4メソッドのうち必要なものだけをoverrideできるようにするアダプタ */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
