# Caster(配信アプリ) — tmn-caster

古いAndroidスマホをカメラ端末として使い、シグナリングWorker経由でWebRTC配信するネイティブアプリ。

> **注記:** この開発環境にはAndroid SDKが無いため、ここでのソースコードは Android Studio 等
> Android SDKが整った環境でのビルド・実機確認が未実施。`gradlew` / `gradle-wrapper` は用意済みなので、
> Android SDKへのパスを通した上で `./gradlew assembleDebug` を実行して検証すること。

## 構成

| ファイル | 役割 |
|---|---|
| `Config.kt` | シグナリングURL・ルームトークンの設定(デプロイ前に書き換え) |
| `SignalingClient.kt` | シグナリングWorkerとのWebSocket接続(OkHttp) |
| `SdpObserverAdapter.kt` | `org.webrtc.SdpObserver` の必要メソッドのみoverrideするためのアダプタ |
| `StreamingService.kt` | 常時起動のForeground Service。WS常時接続、`viewer-joined`受信でカメラ起動・Offer送信、`viewer-left`で停止するオンデマンド配信ロジック本体 |
| `MainActivity.kt` | カメラ権限リクエスト、バッテリー最適化除外の案内、サービス起動 |
| `CatPersonDetector.kt` | MediaPipe Tasks Visionによる猫・人検知(配信中は30秒間隔、待機中も15分に1回の「見回り」で実行) |

## 事前設定

`app/src/main/java/org/wagaya/tmn/caster/Config.kt` を編集し、デプロイ済みシグナリングWorkerのURLと
Viewerと共有するルームトークンを設定する。

```kotlin
object Config {
    const val SIGNALING_URL = "wss://tmn-signaling.<your-subdomain>.workers.dev"
    const val ROOM_TOKEN = "<room-token>"
    const val ACCESS_PASSWORD = "" // シグナリングWorker側でACCESS_PASSWORDを設定している場合のみ(任意)
}
```

## ビルド(Android SDKが利用可能な環境で)

```sh
./gradlew assembleDebug
```

Android Studioでこのディレクトリを開いても良い。`minSdk = 26`、`compileSdk` / `targetSdk = 36`
(Android 8.0〜16に対応、Android 17端末上でも後方互換で動作する)。
Android 14〜17対応の詳しい検討経緯は
[docs/android-14-17-support.md](../docs/android-14-17-support.md) を参照。

## 猫・人検知

カメラ映像に猫・人が映っているかをオンデバイスで検知し、常駐通知のテキストに反映する
(例:「配信中(猫を検知)」「待機中(前回の見回りで猫を検知)」)。配信中は30秒間隔、
視聴者がいない待機中も15分に1回・5秒間だけカメラを起動する「見回り」で検知する。
EfficientDet-Lite0モデル(`assets/efficientdet_lite0.tflite`、リポジトリに同梱済み)を
MediaPipe Tasks Visionで実行する。設計判断の詳細は
[docs/cat-person-detection.md](../docs/cat-person-detection.md) を参照。

## 既知の未検証事項(実機確認が必要)

- CameraX依存は宣言してあるが、実際のカメラキャプチャは `org.webrtc.Camera2Enumerator` /
  `Camera2Capturer` を使用(WebRTC SDKが自前でCamera2を制御するため、CameraXとの二重制御を避けた)。
  ローカルプレビューUIが必要になった場合はCameraXのPreview UseCaseを別途追加する。
- Foreground Service化(`foregroundServiceType="camera"`)はAndroid 14(API 34)の権限要件に沿っている:
  `FOREGROUND_SERVICE_CAMERA`権限、カメラ権限の事前取得、Android 13+向けの`POST_NOTIFICATIONS`
  実行時権限リクエスト(無いと常駐通知が表示されないため)を実装済み。ただし実機での起動・
  画面消灯後の継続動作は未検証。
- バッテリー最適化除外・スリープ設定は `MainActivity` からダイアログを開くのみで、実際の除外確認は行っていない。
- シグナリングWebSocketが切断された場合、`SignalingClient`が指数バックオフ(1秒〜15秒)で自動的に
  再接続する(viewer/app.jsと同じ方式)。ただしこれはプロセスが生きている間の再接続のみで、
  Androidがフォアグラウンドサービスごとプロセスを終了させた場合の復旧はカバーしない。
  `WorkManager` 依存は将来その定期的なプロセス生存確認・再起動(plan.md M6)向けに追加済みだが、
  実装はまだ行っていない。
- 猫・人検知(`CatPersonDetector`)は実機での推論精度・レイテンシ・APKサイズ増加分が未確認。
  詳細は [docs/cat-person-detection.md](../docs/cat-person-detection.md) の既知の未検証事項を参照。
