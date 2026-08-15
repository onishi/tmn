# TMN 開発計画

README.md の設計(MVP設計・発展計画フェーズ2〜4)を、実装単位まで分解した開発計画。
各マイルストーンは「単体で動作確認できる」粒度に分割し、依存関係の少ないものから着手する。

---

## マイルストーン一覧

| M# | 内容 | フェーズ対応 |
|---|---|---|
| M0 | 環境準備・アカウント整備 | 1.5 手順1 |
| M1 | シグナリングWorker(最小実装) | 1.5 手順2 |
| M2 | Viewer(視聴Webアプリ、モック配信で疎通) | 1.5 手順4 |
| M3 | Broadcaster(配信Androidアプリ、最小実装) | 1.5 手順3 |
| M4 | E2E結合(WebRTC映像疎通) | 1.5 手順3〜5 |
| M5 | 常設・デプロイ | 1.5 手順6〜7 |
| M6 | 安定性・利便性向上 | フェーズ2 |
| M7 | 見守り機能強化 | フェーズ3 |
| M8 | 本格運用・拡張 | フェーズ4 |

---

## M0. 環境準備・アカウント整備

- [ ] Cloudflareアカウント作成
- [ ] Workers / Pages / Realtime (TURN) を有効化
- [ ] `wrangler` CLIセットアップ、ログイン確認
- [x] リポジトリ構成決定(モノレポ想定: `signaling/`, `viewer/`, `android/`)
- [ ] Android Studio + Kotlin開発環境セットアップ
- [ ] 配信用の古いスマホを用意し、開発者オプション・USBデバッグ有効化

> 上記のCloudflareアカウント作成・実機準備はユーザー環境が必要なため、この開発環境では未着手。
> それ以外(M1・M2の実装/ローカル疎通確認)は先行して完了させた。

**完了条件:** `wrangler whoami` 成功、Android実機/エミュレータにデバッグビルドが転送できる。

---

## M1. シグナリングWorker(最小実装)

Cloudflare Workers + Durable Objects でルームごとのWebSocket中継を作る。

- [x] Durable Object `Room` クラスの雛形作成(1ルーム = 1トークン)
- [x] WebSocket接続の確立・切断ハンドリング(Broadcaster/Viewerの2ロールを区別)
- [x] メッセージ中継ロジック(Offer/Answer/ICE candidateをそのまま相手側へフォワード)
- [x] Viewer接続をトリガーにBroadcasterへ「配信開始」通知を送る仕組み
- [x] ルームトークンのバリデーション(推測困難な文字列、URLパスまたはクエリで指定)
- [x] ローカル(`wrangler dev`)でWebSocketクライアント(`test/manual-relay-check.mjs`)による疎通テスト
- [ ] Cloudflareへデプロイし、疎通確認

**完了条件:** 2つのWebSocketクライアント(配信役・視聴役を模擬)が同一ルームでメッセージを中継できる。

---

## M2. Viewer(視聴Webアプリ、モック配信で疎通)

Broadcaster(配信Androidアプリ)が未完成でも進められるよう、先にWeb側だけで検証する。

- [x] 静的ページ雛形(HTML/CSS/JS、フレームワークは軽量なもの or vanilla)
- [x] URLの `?room=xxxxxxxx` トークン読み取り
- [x] シグナリングWorkerへのWebSocket接続
- [x] RTCPeerConnection生成、Answer送信ロジック実装
- [x] `<video>` タグへのリモートストリーム表示
- [x] ブラウザ2タブ(片方をChromeの `getUserMedia` でカメラ映像を模擬配信)でWebRTC疎通確認(Playwright + fake device、320x240映像がvideo要素に到達しPeerConnectionが`connected`になることを確認)
- [ ] Cloudflare Pagesへのデプロイ設定(プレビュー環境)

**完了条件:** ブラウザ同士でカメラ映像がP2P配信できる(Android側なしでWebRTC経路を検証済み)。

---

## M3. Broadcaster(配信Androidアプリ、最小実装)

- [ ] Android Studioプロジェクト作成(Kotlin, minSdk選定)
- [ ] CameraXでカメラプレビュー取得(まずはローカル表示のみで確認)
- [ ] WebRTC Android SDK導入、PeerConnectionFactory初期化
- [ ] シグナリングWorkerとのWebSocket接続(常時維持)
- [ ] 視聴リクエスト受信 → カメラ起動 → PeerConnection確立 → Offer生成、のオンデマンドフロー実装
- [ ] Foreground Service化(通知常駐、画面消灯時も継続)
- [ ] バッテリー最適化除外の設定案内(初回起動時ダイアログ等)
- [ ] エミュレータ/実機での単体動作確認(カメラ取得・WS接続維持)

**完了条件:** アプリを起動後、画面を消してもFGサービスが常駐し、シグナリングWSが切れない。

---

## M4. E2E結合(WebRTC映像疎通)

- [ ] M2のViewer ↔ M3のBroadcasterを同一ルームトークンで接続
- [ ] STUN(`stun.cloudflare.com`)設定、ローカルネットワークでのP2P接続確認
- [ ] 異なるネットワーク(モバイル回線 vs Wi-Fi)でのNAT越え確認、TURNフォールバック動作確認
- [ ] 接続確立までのレイテンシ・映像遅延の実測
- [ ] 異常系確認(Viewer切断、Broadcaster側ネットワーク瞬断、再接続なしでの挙動)

**完了条件:** 実際に自宅のスマホ(配信)と外出先の回線(視聴)でライブ映像が見られる。

---

## M5. 常設・デプロイ(MVP完成)

- [ ] Androidアプリの署名・リリースビルド作成
- [ ] 配信用スマホへインストール、常時電源接続の物理設置
- [ ] スリープ設定オフ・バッテリー最適化除外を本番機に適用
- [ ] 視聴用Webアプリを本番Cloudflare Pagesへデプロイ
- [ ] 視聴用URL(トークン付き)の発行・保管方法decide
- [ ] 最低限のセキュリティ確認(トークン推測困難性、DO側のルームスコープ制御)
- [ ] 数日runningさせて安定性の一次観察(発熱・電池持ち・再接続有無)

**完了条件:** MVPとして日常運用開始。README 1.3 の「含む」スコープを満たす。

---

## M6. 安定性・利便性向上(フェーズ2)

- [ ] TURN利用量モニタリング(Cloudflareダッシュボード定期確認 or 通知)
- [x] Viewer側の自動再接続(シグナリングWS切断時に指数バックオフで再接続、PeerConnectionを作り直す)
- [x] Broadcaster側のシグナリング切断時の即時再接続(`SignalingClient`が指数バックオフで
      自動再接続。viewer/app.jsと同じ方式。ビルド未検証)
- [ ] WorkManagerによる定期的なプロセス生存確認・再起動(FGサービスごとプロセスが終了した場合の復旧)
- [ ] Wi-Fi切断・アプリ復帰時のセッション再構築
- [x] 複数視聴者対応の設計検討(P2P複数接続 or SFU移行の比較): [docs/multi-viewer-design.md](./docs/multi-viewer-design.md)参照。
      まずP2P複数接続を実装し、視聴者5人以上等で実際にボトルネックになった段階でSFU移行を検討する方針とした(実装は未着手)
- [x] 簡易パスワード認証の実装(シグナリング層): `ACCESS_PASSWORD`シークレットを設定すると
      `?password=`の一致がWS接続の必須条件になる。viewer/Androidの両方に対応。
      未設定時は従来通りルームトークンのみで動作(後方互換)

**完了条件:** 接続断からの自動復旧が機能し、認証なしでは視聴URLにアクセスできない。

---

## M7. 見守り機能強化(フェーズ3)

- [ ] フレーム差分による動体検知ロジック(Broadcaster内)
- [ ] 動体検知トリガーでの自動配信開始(オンデマンド方針との統合)
- [ ] Firebase Cloud Messaging連携、Push通知送信
- [ ] 録画バッファ実装(イベント前後を保存)
- [ ] Cloudflare R2 or Firebase Storageへのクリップアップロード
- [ ] 双方向音声(呼びかけ機能)の追加
- [ ] 複数カメラ対応(端末切り替えUI、ルーム分割設計)

**完了条件:** 動体検知→通知→クリップ確認、という一連の見守りフローが機能する。

---

## M8. 本格運用・拡張(フェーズ4)

- [ ] 視聴者数・TURN従量課金状況のレビュー、SFU移行 or 自宅サーバー移行の判断
- [ ] (必要な場合)Cloudflare Realtime SFU導入
- [ ] (必要な場合)iOS版Broadcaster検討(Kotlin Multiplatform等)
- [ ] 他センサー(温度・給餌タイミング等)とのダッシュボード統合
- [ ] 招待リンクによる家族・複数デバイス共有機能

**完了条件:** 個別要件に応じて都度判断(このフェーズは需要が顕在化してから着手)。

---

## 進め方の指針

- M1(シグナリング)とM2(Viewer)は並行着手可能。M3(Broadcaster)はWebRTC SDK学習コストが高いため先に着手して並走させるのが望ましい。
- MVP完成の定義はM5まで。M6以降は運用しながら優先度を都度見直す。
- 各マイルストーン内のチェックボックスは実装順の目安であり、厳密な順序拘束ではない。
