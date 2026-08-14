# tmn-broadcaster (配信Androidアプリ)

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

## 事前設定

`app/src/main/java/com/tmn/broadcaster/Config.kt` を編集し、デプロイ済みシグナリングWorkerのURLと
視聴側と共有するルームトークンを設定する。

```kotlin
object Config {
    const val SIGNALING_URL = "wss://tmn-signaling.<your-subdomain>.workers.dev"
    const val ROOM_TOKEN = "<room-token>"
}
```

## ビルド(Android SDKが利用可能な環境で)

```sh
./gradlew assembleDebug
```

Android Studioでこのディレクトリを開いても良い。`minSdk = 26` を想定。

## 既知の未検証事項(実機確認が必要)

- CameraX依存は宣言してあるが、実際のカメラキャプチャは `org.webrtc.Camera2Enumerator` /
  `Camera2Capturer` を使用(WebRTC SDKが自前でCamera2を制御するため、CameraXとの二重制御を避けた)。
  ローカルプレビューUIが必要になった場合はCameraXのPreview UseCaseを別途追加する。
- Foreground Service化(`foregroundServiceType="camera"`)はAndroid 14以降の権限要件に沿っているが、
  実機での起動・画面消灯後の継続動作は未検証。
- バッテリー最適化除外・スリープ設定は `MainActivity` からダイアログを開くのみで、実際の除外確認は行っていない。
- `WorkManager` 依存は追加済みだが、自動再接続ロジックの実装はフェーズ2(plan.md M6)で行う。
