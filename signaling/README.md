# tmn-signaling

Cloudflare Workers + Durable Objects によるWebRTCシグナリング中継。
ルームトークンごとに1つの `Room` Durable Objectが割り当てられ、Caster(配信アプリ、
プロトコル上は`role=caster`)と複数のViewer(視聴アプリ、プロトコル上は`role=viewer`)間の
Offer/Answer/ICE candidateを、視聴者ごとに一意な`viewerId`で宛先を区別して中継する
(複数視聴者の同時接続に対応。設計は[docs/multi-viewer-design.md](../docs/multi-viewer-design.md)参照)。

## プロトコル

`wss://<host>/room/<roomToken>?role=caster|viewer` に接続する。
`ACCESS_PASSWORD` を設定している場合は `&password=<パスワード>` も必須(下記「簡易パスワード認証」参照)。

- Viewer接続時、Roomがそのviewerに一意な`viewerId`を発行し`{"type":"welcome","viewerId":"..."}`を
  返す。以降、そのViewerが送るanswer/ice-candidateにはこの`viewerId`を含める必要がある
- Viewer接続時、Casterへ `{"type":"viewer-joined","viewerId":"...","browserName":"..."}` を送信
  (オンデマンド配信のトリガー)。`browserName`はUser-Agentから簡易判定した表示用の文字列
- Viewer切断時、Casterへ `{"type":"viewer-left","viewerId":"..."}` を送信
- Casterが(再)接続した時点で既に接続中のViewerがいれば、それぞれの`viewer-joined`を
  まとめて通知する(Caster再起動後にViewerごとのPeerConnectionを張り直してもらうため)
- Caster発のoffer/ice-candidateは`viewerId`で指定した1人のViewerにのみ中継する
  (メッセージ内に`viewerId`を含める必要がある)
- Viewer発のanswer/ice-candidateはそのままCasterへ中継する(Caster側が`viewerId`を見て
  対応するPeerConnectionへ振り分ける)
- Caster発の `{"type":"detection-status","hasAnimal":bool,"hasPerson":bool}` は、Viewer全員へ
  中継すると同時に直近の1件をRoom内にキャッシュする(下記「動物・人検知ステータスの確認」参照)
- Roomは20秒間隔でViewerへ`{"type":"ping"}`を送り、45秒間応答(`{"type":"pong"}`または他の
  メッセージ)が無いセッションは強制的に切断する(スマホのバックグラウンド化等でWebSocketの
  closeイベントが届かないまま接続だけ残ることがあるための生存確認)
- Viewerの入退室・生存確認による強制切断のたびに、現在の視聴者数を全Viewerへ
  `{"type":"viewer-count","count":number}`としてブロードキャストする

## 動物・人検知ステータス、視聴者数の確認(視聴を開始せずに)

`GET /room/<roomToken>/status`(`ACCESS_PASSWORD` 設定時は `?password=` も必須)で、
Casterが直近に報告した検知結果と現在の視聴者数を確認できる。WebSocketアップグレードは
不要な通常のGETで、視聴用の接続(=Caster側のカメラ起動)を伴わない。

```json
{"hasAnimal": true, "hasPerson": false, "updatedAtMs": 1735689600000, "viewerCount": 0}
```

検知結果が一度も報告が無い場合は `{"hasAnimal": null, "hasPerson": null, "updatedAtMs": null}` の
部分がnullになる(`viewerCount`は常に実数)。Viewer(Cloudflare Pages)とはオリジンが異なるため、
レスポンスに`Access-Control-Allow-Origin: *` を付与している。詳細は
[docs/cat-person-detection.md](../docs/cat-person-detection.md) を参照。

## TURN(中継サーバー)認証情報の発行

`GET /turn-credentials`(`ACCESS_PASSWORD` 設定時は `?password=` も必須)で、Cloudflare Calls
(Realtime)のTURN Key APIから短命(24時間)のTURN認証情報を発行する。同じWi-Fiルーター配下
同士でも、ルーターがNATヘアピンに対応していないとSTUNのみではP2P接続が確立できないことが
あるため、STUN(`stun.cloudflare.com`)に加えたフォールバックとして、Viewer・Caster双方が
接続確立時に取得して`RTCConfiguration`/`iceServers`に加える。

`TURN_APP_ID`/`TURN_APP_TOKEN`(Cloudflareダッシュボードで発行するCalls App)が未設定の環境では
`{"iceServers": null}`を返し、クライアント側はSTUNのみにフォールバックする。
`TURN_APP_TOKEN`はこのエンドポイント内でのみ使用し、クライアントには短命の
username/credentialペアだけを渡す。

## 簡易パスワード認証(任意)

ルームトークンに加えて、共有パスワードによる認証を追加できる(README 1.4 / plan.md M6)。
`ACCESS_PASSWORD` を設定すると、WebSocket接続時に `?password=` パラメータの一致が必須になる。
未設定の場合はこのチェックはスキップされ、ルームトークンのみで従来通り動作する。

- ローカル開発: `signaling/.dev.vars.example` を `.dev.vars` にコピーし `ACCESS_PASSWORD` を設定(`.dev.vars` はコミットしない)
- 本番: `npx wrangler secret put ACCESS_PASSWORD` でCloudflare側にシークレットとして登録する

Viewer側は `config.js` の `accessPassword`、Caster側は `Config.kt` の `ACCESS_PASSWORD` に同じ値を設定する。

## 開発

```sh
npm install
npm run dev        # ローカルで http://localhost:8787 に起動
npm run typecheck
```

ローカルサーバー起動中に、中継ロジックの手動確認スクリプトを実行できる:

```sh
node test/manual-relay-check.mjs
```

`.dev.vars` で `ACCESS_PASSWORD` を設定している場合は、環境変数 `TEST_ACCESS_PASSWORD` に同じ値を渡して実行する:

```sh
TEST_ACCESS_PASSWORD=<設定した値> node test/manual-relay-check.mjs
```

## デプロイ

```sh
npx wrangler login
npm run deploy
```

デプロイ後、簡易パスワード認証を使う場合は `npx wrangler secret put ACCESS_PASSWORD` でシークレットを登録する。
TURNフォールバックを使う場合は、Cloudflareダッシュボードで発行したCalls App IDとTokenを
`npx wrangler secret put TURN_APP_ID` / `npx wrangler secret put TURN_APP_TOKEN` で登録する
(未設定でもSTUNのみで動作するため必須ではない)。
