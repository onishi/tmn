# 猫・人の検知(Caster)

Caster(配信アプリ)がカメラ映像内に猫・人が映っているかをオンデバイスで検知する機能の
設計記録。plan.md M7「見守り機能強化」本体(動体検知→通知→録画の一連のフロー)とは別に、
「映っているものが何かを認識できるか」という検知能力そのものを先行実装したもの。
この開発環境にはAndroid SDKが無くビルド・実機検証ができないため、判断の根拠を残す。

## 採用した構成

- **推論ライブラリ**: MediaPipe Tasks Vision(`com.google.mediapipe:tasks-vision:0.10.29`)
  - TensorFlow Lite Task Library(旧来の高レベルAPI)はメンテナンスモードに入っており、
    Googleは新規開発にMediaPipe Tasksを使うよう明示的に案内している(2026年時点)
  - minSdk要件はAPI 21で、本アプリのminSdk 26を満たす
- **モデル**: EfficientDet-Lite0(COCO 2017学習済み、メタデータ付き、約4.5MB)
  - `https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite`
    から取得し、`android/app/src/main/assets/efficientdet_lite0.tflite` にコミット済み
  - ダウンロードして中身を確認済み: 90クラスのCOCOラベルに `person` と `cat` が
    個別に含まれることを確認した
  - Apache 2.0ライセンス(TensorFlow公式モデル配布)で、リポジトリへの同梱に問題はない

## 実行方法(バッテリー消費への配慮)

- `StreamingService.kt` の配信用`VideoTrack`に`VideoSink`を追加してフレームをタップする
  (`Camera2Capturer`本体やエンコードパイプラインには影響しない、独立した経路)
- 推論は**5秒に1回**に間引く(`DETECTION_INTERVAL_MS`)。毎フレーム推論すると
  古いスマホのCPU/バッテリー負荷が無視できないため
- 推論自体もWebRTCのキャプチャ/エンコードスレッドをブロックしないよう、専用の
  `ExecutorService`(シングルスレッド)上で実行する
- 検知は**配信中のみ**動作する(視聴者が接続していない間はカメラ自体を起動しない、
  既存のオンデマンド配信方針とライフサイクルを合わせている)。常時監視ではない

## フレーム変換について

WebRTCの`VideoFrame`はI420(YUV420 planar)形式で、MediaPipeの`BitmapImageBuilder`は
`android.graphics.Bitmap`を要求する。変換方法として以下を比較した:

- **色空間変換を自前実装する(YUV→RGB数式を直接書く)**: 最速だが、クランプ処理や
  ストライド計算を誤りやすく、この開発環境ではビルド・実機確認ができないため
  誤りに気づけないリスクが大きい
- **採用: I420→NV21(バイトの並べ替えのみ、色空間計算不要)→`android.graphics.YuvImage`で
  JPEGエンコード→`BitmapFactory`でデコード**: Android標準APIに変換を任せるため、
  自前の数式ミスが入り込む余地がない。JPEG往復のオーバーヘッドは生じるが、
  5秒に1回の間引き実行なので実用上問題にならないと判断した

正しさの確信度を優先した設計であり、実機でのプロファイリングでボトルネックになるようなら
自前変換への置き換えを検討する。

## 検知結果の使い道(現時点のスコープ)

- Foreground Serviceの常駐通知テキストに反映するのみ(例: 「配信中(猫を検知)」)
- Push通知・録画・クリップ保存(plan.md M7本体)は今回のスコープ外。実装する場合は
  この検知結果をトリガーとして使う設計になる見込み
- 視聴Webアプリ(Viewer)側への検知結果の伝達(データチャンネル等)も未実装。
  現状はAndroid側の通知でしか確認できない

## 既知の未検証事項(実機確認が必要)

- モデルの推論精度・実際のレイテンシは未計測(古いスマホでのCPU性能次第で
  5秒間隔が適切か調整が必要になる可能性がある)
- `MediaPipe Tasks Vision`はネイティブライブラリを含むため、APKサイズが増加する
  (依存追加分で数十MB程度増える見込み。ストレージが少ない古いスマホでは容量を確認すること)
- I420→NV21変換のストライド計算は静的レビューのみで検証しており、実機でのフレーム
  (解像度1280x720、`capturer.startCapture(1280, 720, 30)`)での動作は未確認
- モデル読み込み失敗時は検知機能なしで配信自体は継続する設計(`try/catch`でフォールバック)
  だが、この経路(失敗パス)自体も未検証
