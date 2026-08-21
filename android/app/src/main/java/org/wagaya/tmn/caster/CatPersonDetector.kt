package org.wagaya.tmn.caster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.webrtc.VideoFrame

/**
 * カメラ映像フレームにペットっぽい動物(猫・犬・鳥)・人が映っているかをオンデバイスで検知する。
 * EfficientDet-Lite0(COCO学習済み、90クラス中に person・cat・dog・bird を含む)を
 * MediaPipe Tasks Vision の ObjectDetector で実行する。
 *
 * 呼び出し側は [detectFrameAsync] に渡す前に必ず [VideoFrame.retain] しておくこと。
 * 変換・推論後にこのクラスが責任を持って [VideoFrame.release] する。
 */
class CatPersonDetector(
    context: Context,
    onResult: (CatPersonDetectionResult) -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val detector: ObjectDetector = run {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setMaxResults(MAX_RESULTS)
            .setResultListener { result, _ ->
                var hasAnimal = false
                var hasPerson = false
                val seen = mutableListOf<String>()
                for (detection in result.detections()) {
                    for (category in detection.categories()) {
                        seen.add("${category.categoryName()}=${category.score()}")
                        when (category.categoryName()) {
                            "cat", "dog", "bird" -> hasAnimal = true
                            "person" -> hasPerson = true
                        }
                    }
                }
                Log.d(TAG, "detection result: ${if (seen.isEmpty()) "(none above threshold)" else seen.joinToString()}")
                onResult(CatPersonDetectionResult(hasAnimal, hasPerson))
            }
            .setErrorListener { error -> Log.e(TAG, "detection error", error) }
            .build()
        ObjectDetector.createFromOptions(context, options)
    }

    /**
     * [frame] の所有権(retain済みであること)を受け取り、バックグラウンドスレッドで
     * I420→Bitmap変換のうえ非同期に検知する。結果は初期化時に渡した [onResult] に届く。
     */
    fun detectFrameAsync(frame: VideoFrame, timestampMs: Long) {
        executor.execute {
            try {
                val i420 = frame.buffer.toI420()
                if (i420 == null) {
                    Log.w(TAG, "failed to convert frame to I420")
                    return@execute
                }
                try {
                    val bitmap = i420ToBitmap(i420, frame.rotation)
                    val mpImage = BitmapImageBuilder(bitmap).build()
                    detector.detectAsync(mpImage, timestampMs)
                } finally {
                    i420.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "detectFrameAsync failed", e)
            } finally {
                frame.release()
            }
        }
    }

    fun close() {
        executor.shutdown()
        detector.close()
    }

    /** WebRTCのI420(プレーナー)バッファを、Android標準APIだけでBitmapへ変換する。
     * 色空間変換を自前実装せず、あえてYuvImage→JPEG→Bitmapという遠回りな経路を使うのは、
     * ビルド・実機検証ができないこの開発環境では、既存の実装が確定しているAPIに乗せて
     * 正しさの確信度を上げる方を優先したため(検知は30秒に1回程度の間引き実行なので、
     * JPEG変換のオーバーヘッドは実用上問題にならない)。 */
    private fun i420ToBitmap(buffer: VideoFrame.I420Buffer, rotationDegrees: Int): Bitmap {
        val nv21 = i420ToNv21(buffer)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, buffer.width, buffer.height, null)
        val output = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, buffer.width, buffer.height), 90, output)
        val jpegBytes = output.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: throw IllegalStateException("failed to decode frame as bitmap")
        // カメラセンサーはランドスケープ基準で、端末の向きに応じたrotationはVideoFrame側の
        // メタデータとして別で渡ってくる(WebRTCの標準的な仕様で、バッファ自体には
        // 反映されていない)。これを適用しないと縦持ち撮影時に画像が横倒しのまま
        // 検知エンジンに渡ってしまい、動物・人をほぼ検知できなくなる
        if (rotationDegrees == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    private fun i420ToNv21(buffer: VideoFrame.I420Buffer): ByteArray {
        val width = buffer.width
        val height = buffer.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val ySize = width * height
        val nv21 = ByteArray(ySize + chromaWidth * chromaHeight * 2)

        val yPlane = buffer.dataY
        var dstIndex = 0
        for (row in 0 until height) {
            val rowStart = row * buffer.strideY
            for (col in 0 until width) {
                nv21[dstIndex++] = yPlane.get(rowStart + col)
            }
        }

        // NV21はYプレーンの後にV,Uの順で1画素おきに交互配置する
        val uPlane = buffer.dataU
        val vPlane = buffer.dataV
        for (row in 0 until chromaHeight) {
            val uRowStart = row * buffer.strideU
            val vRowStart = row * buffer.strideV
            for (col in 0 until chromaWidth) {
                nv21[dstIndex++] = vPlane.get(vRowStart + col)
                nv21[dstIndex++] = uPlane.get(uRowStart + col)
            }
        }
        return nv21
    }

    data class CatPersonDetectionResult(val hasAnimal: Boolean, val hasPerson: Boolean)

    companion object {
        private const val TAG = "CatPersonDetector"
        private const val MODEL_ASSET_PATH = "efficientdet_lite0.tflite"
        private const val SCORE_THRESHOLD = 0.3f
        private const val MAX_RESULTS = 5
    }
}
