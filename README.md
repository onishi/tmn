# TMN - Tail Monitoring Network

## 概要

古いスマホをカメラ端末として活用し、WebRTCで外出先から自宅の猫をライブ観察できるサービス。専用サーバー不要、シグナリングのみ軽量な仲介サービスを利用する。

## 用語(ユビキタス言語)

本プロジェクトのコード・ドキュメント全体で、2つのアプリを次の名称で統一して呼ぶ。

| 名称 | 説明 | 対応するコード上の識別子 |
|---|---|---|
| **Caster**(配信アプリ) | 古いAndroidスマホにインストールし、カメラ映像を配信するネイティブアプリ | ディレクトリ `android/`、パッケージ `org.wagaya.tmn.caster`、シグナリングプロトコルの `role=caster` |
| **Viewer**(視聴アプリ) | 外出先のブラウザから配信映像を見るための静的Webアプリ | ディレクトリ `viewer/`、シグナリングプロトコルの `role=viewer` |

「配信用スマホ」「配信端末」はCasterアプリをインストールする**物理的なハードウェア**を指し、
Caster(アプリそのもの)とは区別して使う。

---

## 1. ミニマム設計(MVP)

### 1.1 構成図

```
[Caster(配信アプリ、Androidネイティブアプリ)]
      │  WebRTC(STUN: stun.cloudflare.com)
      ▼
[Cloudflare Realtime TURN](NAT越えが必要な時だけ経由)
      │
      ▼
[Viewer(視聴アプリ)] ← Cloudflare Pagesでホスティング
      ▲
      │  シグナリング(接続情報交換・視聴リクエスト通知)
[Cloudflare Workers + Durable Objects]
```

全構成要素をCloudflareに統合することで、契約先・管理画面を1つにまとめられる。配信端末はAndroidを採用し、Casterはネイティブ(Kotlin)で実装する(フォアグラウンドサービスにより画面消灯状態でも安定して配信を継続できるため。PWAは開発は手軽だが、バックグラウンド動作の制約が大きく監視カメラ用途には不向き)。

### 1.2 コンポーネント

| コンポーネント | 役割 | 技術 |
|---|---|---|
| Caster(配信アプリ) | カメラ映像取得・配信 | Androidネイティブアプリ (Kotlin, CameraX, WebRTC Android SDK, Foreground Service) |
| Viewer(視聴アプリ) | 映像受信・表示 | 静的Webページ、Cloudflare Pagesでホスティング |
| シグナリング | 接続情報の仲介 | Cloudflare Workers + Durable Objects(WebSocket) |
| NAT越え(STUN) | P2P接続確立の第一段階 | stun.cloudflare.com(無料・無制限) |
| NAT越え(TURN) | P2P直結できない場合の中継 | Cloudflare Realtime TURN(月1,000GBまで無料、以降$0.05/GB) |

### 1.3 機能スコープ

**含む:**
- カメラ映像の一方向配信(Caster → Viewer)
- 1対1接続(視聴者1人を想定)
- CasterはAndroid端末にインストールし、Foreground Serviceとして常時起動
- **オンデマンド配信**: 平常時はシグナリング用WebSocket接続のみ維持し待機。視聴者がアクセスしてきたタイミングでシグナリング経由の通知を受け、カメラ起動・PeerConnection確立・エンコードを開始する(常時配信ではない)
- 画面消灯・バックグラウンドでも配信継続(Foreground Service + バッテリー最適化除外設定)

**含まない(MVPでは):**
- 録画・アーカイブ
- 複数視聴者への同時配信
- 音声・双方向通話
- 通知機能(動体検知など)
- 認証・アクセス制御(最低限のみ)

> **設計メモ:** 古いスマホは新品に比べてバッテリーの劣化・放熱性能の低下が進んでいることが多い。カメラ取得・WebRTCエンコードを24時間常時実行し続けると発熱やバッテリー劣化が加速するため、MVPの時点から「視聴者が接続したときだけ配信を開始する」オンデマンド方式を採用する。常時起動するのはFGサービス上の軽量なシグナリングWebSocket接続のみとし、実際の映像パイプラインは視聴リクエストをトリガーに起動・終了する。

### 1.4 最低限のセキュリティ

- 視聴用URLに推測困難なトークンを付与(例: `?room=xxxxxxxx`)
- Durable Objectsのルーム(Object ID)をトークンに紐付け、該当ルームの接続情報のみ読み書き可能にする
- シグナリング通信はCloudflare経由のWSS/HTTPSで常に暗号化される。映像・音声ストリーム自体もWebRTC標準のDTLS-SRTPによりP2P区間・TURN区間ともエンドツーエンドで暗号化される(追加実装不要)
- 簡易パスワード認証(任意):シグナリングWorkerに`ACCESS_PASSWORD`シークレットを設定すると、ルームトークンに加えて共有パスワードの一致がWebSocket接続の必須条件になる(実装済み、`signaling/README.md`参照)

### 1.5 開発ステップ

1. Cloudflareアカウント作成、Workers/Pages/Realtimeを有効化
2. シグナリング用Workerの実装(Durable Objectsでルームごとの接続情報をWebSocket経由で中継。Viewerの接続をトリガーにCasterへ「配信開始」通知を送る仕組みを含む)
3. Caster(配信Androidアプリ)実装(Android Studio, Kotlin。シグナリングWorkerとのWebSocket接続を常時維持し、視聴リクエスト受信時にCameraXでカメラ取得 → WebRTC Android SDKでRTCPeerConnection作成 → Offer生成、という流れをオンデマンドで実行。Foreground Serviceとして起動し画面消灯・バックグラウンドでも待機・配信を継続)
4. Viewer(視聴Webアプリ)実装(シグナリングWorkerに接続要求を送信 → Answer取得 → 映像受信・表示)
5. STUN(stun.cloudflare.com)を優先設定、接続できない場合のみCloudflare Realtime TURNにフォールバック(ICEエージェントが候補生成・接続性チェックを通じて自動的に行うため、アプリ側で手動制御する必要はない)
6. Casterをビルド・署名し、配信用スマホにインストール。常時電源に接続した状態で設置し、端末のスリープ設定・バッテリー最適化除外も設定
7. ViewerをCloudflare Pagesにデプロイ

### 1.6 想定コスト

- Workers/Durable Objects:無料枠内(個人用途のリクエスト数なら十分収まる)
- STUN:無料・無制限(stun.cloudflare.com)
- TURN:月1,000GBまで無料、超過分は$0.05/GB(オンデマンド配信により視聴していない時間の転送量が発生しないため、常時配信よりさらに無料枠に収まりやすい想定)
- ホスティング:Cloudflare Pages 無料枠
- **合計: ¥0〜(TURN利用量が極端に多い場合のみ従量課金)**

---

## 2. 発展計画

### フェーズ2: 安定性・利便性の向上

- **TURN利用量の監視**:Cloudflareダッシュボードで無料枠(月1,000GB)の消費状況を定期確認、超過が見込まれる場合は視聴頻度の見直しやSFU移行を検討
- **自動再接続**:Wi-Fi切断やアプリ復帰時に自動でセッション再構築
- **複数視聴者対応**:Cloudflare Realtime SFU導入を検討(同一プラットフォーム内で移行可能)、または簡易的にP2P接続を複数張る方式
- **認証機能**:簡易パスワードログイン、Cloudflare Access等の利用

### フェーズ3: 見守り機能の強化

- **動体検知・通知**:Caster側でフレーム差分を検出しPush通知(Firebase Cloud Messaging)。動体検知時のみ配信を自動開始する運用にすれば、オンデマンド配信方針とも自然に統合できる。なお「映っているのが猫か人か」を認識する機能自体は先行実装済み([docs/cat-person-detection.md](./docs/cat-person-detection.md))
- **録画・クリップ保存**:一定時間のバッファを保持し、イベント前後を自動保存(Cloudflare R2やFirebase Storage)
- **音声対応**:双方向音声で呼びかけ機能
- **複数カメラ対応**:古いスマホを複数台設置し、切り替えて閲覧

### フェーズ4: 本格運用・拡張

- **専用サーバー移行の検討**:視聴者が大幅に増えTURN従量課金が無視できなくなった場合、Cloudflare Realtime SFUへの本格移行、またはRaspberry Pi + nginx-rtmp等の自宅サーバー案も比較検討
- **CasterのiOS対応検討**:必要であればiOS版もKotlin Multiplatform等で追加開発
- **ダッシュボード化**:温度・給餌タイミングなど他センサーとの統合
- **家族・複数デバイス共有**:招待リンクによるマルチユーザー対応

---

## 3. 技術選定の判断基準(将来の見直し用)

| 状況 | 対応 |
|---|---|
| 視聴者が2〜3人に増えた | P2Pの複数接続で対応可能、様子見 |
| 視聴者が5人以上、または常時録画したい | SFU導入 or 自宅サーバーへの移行を検討 |
| 動体検知など常時稼働の処理が必要になった | Raspberry Pi等の常時稼働サーバーを追加 |
| 外部公開せず家族限定にしたい | Tailscale等のVPNへの切り替えも選択肢 |

---

## 4. 参考技術スタック一覧

- **Caster**: Kotlin, CameraX, WebRTC Android SDK, Foreground Service, WorkManager(再接続制御)
- **Viewer**: WebRTC(ブラウザ標準API)
- **シグナリング**: Cloudflare Workers + Durable Objects(WebSocket)
- **STUN/TURN**: Cloudflare Realtime(stun.cloudflare.com、TURNは従量課金・月1,000GB無料)
- **ホスティング**: Cloudflare Pages(Viewerのみ)

### 補足: なぜCloudflareに統合するか

- シグナリング・TURN・ホスティングを1つのプラットフォームにまとめることで契約先と管理画面が1つになる
- TURNの無料枠(月1,000GB)は個人の見守り用途であれば通常超えない想定
- Durable Objectsは「ルームごとの状態を持つ軽量サーバー」として動作するため、Firebaseのようなドキュメント指向DBを使うより素のWebSocket実装に近い形でシグナリングを書ける
- 将来的にSFU(Cloudflare Realtime SFU)への移行も同一プラットフォーム内で検討できる(フェーズ2以降の複数視聴者対応時)

---

## 5. 実装

開発計画は [plan.md](./plan.md) を参照。コードは以下のディレクトリに分かれている。

| ディレクトリ | 内容 |
|---|---|
| [`signaling/`](./signaling) | シグナリングWorker(Cloudflare Workers + Durable Objects) |
| [`viewer/`](./viewer) | Viewer(視聴アプリ、静的ページ) |
| [`android/`](./android) | Caster(配信アプリ、Kotlin、Android SDK未検証) |

デプロイ手順は [DEPLOYMENT.md](./DEPLOYMENT.md) を参照。設計検討メモは [`docs/`](./docs) に置く
(例: [複数視聴者対応の設計検討](./docs/multi-viewer-design.md)、
[Android 14〜17対応の検討](./docs/android-14-17-support.md)、
[動物・人の検知、動体検知](./docs/cat-person-detection.md))。
