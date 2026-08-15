# tmn-signaling

Cloudflare Workers + Durable Objects によるWebRTCシグナリング中継。
ルームトークンごとに1つの `Room` Durable Objectが割り当てられ、Broadcaster(配信アプリ、
プロトコル上は`role=broadcaster`)とViewer(視聴アプリ、プロトコル上は`role=viewer`)間の
Offer/Answer/ICE candidateを中継する。

## プロトコル

`wss://<host>/room/<roomToken>?role=broadcaster|viewer` に接続する。
`ACCESS_PASSWORD` を設定している場合は `&password=<パスワード>` も必須(下記「簡易パスワード認証」参照)。

- Viewer接続時、Broadcasterへ `{"type":"viewer-joined"}` を送信(オンデマンド配信のトリガー)
- Viewer切断時、Broadcasterへ `{"type":"viewer-left"}` を送信
- それ以外の全メッセージ(offer/answer/ice-candidate等)は相手ロールへそのまま中継する

## 簡易パスワード認証(任意)

ルームトークンに加えて、共有パスワードによる認証を追加できる(README 1.4 / plan.md M6)。
`ACCESS_PASSWORD` を設定すると、WebSocket接続時に `?password=` パラメータの一致が必須になる。
未設定の場合はこのチェックはスキップされ、ルームトークンのみで従来通り動作する。

- ローカル開発: `signaling/.dev.vars.example` を `.dev.vars` にコピーし `ACCESS_PASSWORD` を設定(`.dev.vars` はコミットしない)
- 本番: `npx wrangler secret put ACCESS_PASSWORD` でCloudflare側にシークレットとして登録する

Viewer側は `config.js` の `accessPassword`、Broadcaster側は `Config.kt` の `ACCESS_PASSWORD` に同じ値を設定する。

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
