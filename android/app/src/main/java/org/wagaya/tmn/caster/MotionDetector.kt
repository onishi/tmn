package org.wagaya.tmn.caster

import org.webrtc.VideoFrame
import kotlin.math.abs

/**
 * フレーム間のY(輝度)プレーンを粗くダウンサンプリングして比較する、軽量な動体検知。
 * [CatPersonDetector]によるMediaPipeでの本格的な動物・人検知は30秒(配信中)〜1分(待機中)
 * 間隔でしか実行しないため、その合間に動きがあってもすぐには反映されない。
 * この動体検知はコストが低い(グリッドサイズ分の整数比較のみ)ため、本検知よりずっと
 * 高頻度に(呼び出し側のスロットル次第だが数秒に1回程度を想定)実行して、動きがあった
 * 場合だけ本検知の間引きタイマーを早期リセットする用途で使う。
 *
 * 呼び出しは同期的・軽量なので、WebRTCのキャプチャスレッド上で直接呼んでよい
 * (本検知のようにExecutorへオフロードする必要はない)。
 */
class MotionDetector(
    private val gridWidth: Int = DEFAULT_GRID_WIDTH,
    private val gridHeight: Int = DEFAULT_GRID_HEIGHT,
    private val threshold: Int = DEFAULT_THRESHOLD,
) {
    private var previousGrid: IntArray? = null

    /**
     * I420のYプレーンを間引きサンプリングし、直前に渡されたフレームとの平均輝度差が
     * 閾値を超えていれば true を返す。初回呼び出しは比較対象が無いため必ず false を返す。
     */
    fun onFrame(buffer: VideoFrame.I420Buffer): Boolean {
        val grid = sampleGrid(buffer)
        val previous = previousGrid
        previousGrid = grid
        if (previous == null) return false

        var totalDiff = 0
        for (i in grid.indices) {
            totalDiff += abs(grid[i] - previous[i])
        }
        val averageDiff = totalDiff / grid.size
        return averageDiff > threshold
    }

    /** カメラの解像度に関わらず一定サイズ(既定16x9)のグリッドへ間引く。補間はせず最近傍点を拾うだけ */
    private fun sampleGrid(buffer: VideoFrame.I420Buffer): IntArray {
        val grid = IntArray(gridWidth * gridHeight)
        val yPlane = buffer.dataY
        var index = 0
        for (gy in 0 until gridHeight) {
            val row = (gy * buffer.height) / gridHeight
            val rowStart = row * buffer.strideY
            for (gx in 0 until gridWidth) {
                val col = (gx * buffer.width) / gridWidth
                grid[index++] = yPlane.get(rowStart + col).toInt() and 0xFF
            }
        }
        return grid
    }

    companion object {
        private const val DEFAULT_GRID_WIDTH = 16
        private const val DEFAULT_GRID_HEIGHT = 9

        // グリッド(0-255スケールの輝度)の平均差分がこの値を超えたら動きありと判定する。
        // カメラのノイズ・自動露出の揺らぎ程度では反応せず、被写体が動いた程度の変化は
        // 拾える値として静的判断で選定した(実機での調整が必要な可能性がある)
        private const val DEFAULT_THRESHOLD = 12
    }
}
