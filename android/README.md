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
| `StreamingService.kt` | 常時起動のForeground Service。WS常時接続、視聴者ごと(viewerId)に個別のPeerConnectionを確立するオンデマンド配信ロジック本体。AUTO/ON/OFFの手動配信モード切替にも対応 |
| `StreamingStatus.kt` | `StreamingService`の状態(シグナリング接続・視聴者一覧・検知結果・配信モード)を`MainActivity`へ橋渡しする`StateFlow`ホルダー |
| `MainActivity.kt` | カメラ権限リクエスト、バッテリー最適化除外の案内、配信状態プレビュー画面(接続状況・カメラプレビュー・検知結果・視聴者一覧・AUTO/ON/OFFトグル) |
| `CameraPreviewBridge.kt` | `StreamingService`が起動した共有カメラの`VideoTrack`/EGLコンテキストを`MainActivity`のプレビューへ橋渡しする |
| `CatPersonDetector.kt` | MediaPipe Tasks Visionによる動物(猫・犬・鳥)・人検知(配信中は30秒間隔、待機中も1分に1回の「見回り」で実行) |
| `MotionDetector.kt` | フレーム差分による軽量な動体検知。動きがあれば本検知(`CatPersonDetector`)の間引きタイマーを早期リセットする |

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

## 動物・人検知、動体検知

カメラ映像に動物(猫・犬・鳥)・人が映っているかをオンデバイスで検知し、常駐通知のテキストと
画面上の「直近の検知」表示に反映する。配信中は30秒間隔、視聴者がいない待機中も1分に1回・
5秒間だけカメラを起動する「見回り」で検知する。EfficientDet-Lite0モデル
(`assets/efficientdet_lite0.tflite`、リポジトリに同梱済み)をMediaPipe Tasks Visionで実行する。
加えて、フレーム差分による軽量な動体検知(`MotionDetector`)を1秒間隔で実行し、動きがあれば
本検知の間引きタイマーを早期リセットして、次のフレームで即座に本検知を行わせる(動きが続く間
ずっと再トリガーし続けないよう、最短10秒のクールダウンを設けている)。設計判断の詳細は
[docs/cat-person-detection.md](../docs/cat-person-detection.md) を参照。

## 複数視聴者・手動配信モード

視聴者ごとに一意な`viewerId`を割り当て、`StreamingService`が視聴者ごとに個別の`PeerConnection`を
確立する(カメラ・映像トラックは全視聴者で共有し、最初の視聴者接続時にのみ起動)。
画面のAUTO/ON/OFFトグルで手動配信モードを切り替えられる(AUTO=視聴者駆動の自動配信、
ON=常時カメラ起動、OFF=配信停止)。一定時間内にICE接続が確立しない視聴者は自動的に切断する。
設計の詳細は[docs/multi-viewer-design.md](../docs/multi-viewer-design.md)を参照。

## TURN(中継サーバー)フォールバック

シグナリングWorker経由でCloudflare CallsのTURN認証情報を取得し(`turnCredentialsUrl()`)、
STUN(`stun.cloudflare.com`)に加えて接続候補に加える。同じWi-Fiルーター配下同士でも、
ルーターがNATヘアピンに対応していないとSTUNのみでは接続できないことがあるための対策。
Worker側で`TURN_APP_ID`/`TURN_APP_TOKEN`が未設定の環境ではSTUNのみにフォールバックする。

## 既知の未検証事項(実機確認が必要)

- CameraX依存は宣言してあるが、実際のカメラキャプチャは `org.webrtc.Camera2Enumerator` /
  `Camera2Capturer` を使用(WebRTC SDKが自前でCamera2を制御するため、CameraXとの二重制御を避けた)。
- Foreground Service化(`foregroundServiceType="camera"`)はAndroid 14(API 34)の権限要件に沿っている:
  `FOREGROUND_SERVICE_CAMERA`権限、カメラ権限の事前取得、Android 13+向けの`POST_NOTIFICATIONS`
  実行時権限リクエスト(無いと常駐通知が表示されないため)を実装済み。ただし実機での起動・
  画面消灯後の継続動作は未検証。
- バッテリー最適化除外・スリープ設定は `MainActivity` からダイアログを開くのみで、実際の除外確認は行っていない。
- シグナリングWebSocketが切断された場合、`SignalingClient`が指数バックオフ(1秒〜15秒)で自動的に
  再接続する(viewer/app.jsと同じ方式)。ただしこれはプロセスが生きている間の再接続のみで、
  Androidがフォアグラウンドサービスごとプロセスを終了させた場合の復旧はカバーしない
  (`WorkManager`による定期的なプロセス生存確認・再起動はplan.md M6として未着手のまま)。
- 動物・人検知(`CatPersonDetector`)・動体検知(`MotionDetector`)は実機での推論精度・レイテンシ・
  閾値の妥当性・APKサイズ増加分が未確認。詳細は
  [docs/cat-person-detection.md](../docs/cat-person-detection.md) の既知の未検証事項を参照。
- 複数視聴者対応(視聴者ごとのPeerConnection)・TURNフォールバックは静的レビューのみで検証しており、
  実際に複数ブラウザ/複数ネットワークからの同時接続は未確認。
