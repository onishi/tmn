# tmn-signaling

Cloudflare Workers + Durable Objects によるWebRTCシグナリング中継。
ルームトークンごとに1つの `Room` Durable Objectが割り当てられ、Caster(配信アプリ、
プロトコル上は`role=caster`)とViewer(視聴アプリ、プロトコル上は`role=viewer`)間の
Offer/Answer/ICE candidateを中継する。

## プロトコル

`wss://<host>/room/<roomToken>?role=caster|viewer` に接続する。
`ACCESS_PASSWORD` を設定している場合は `&password=<パスワード>` も必須(下記「簡易パスワード認証」参照)。

- Viewer接続時、Casterへ `{"type":"viewer-joined"}` を送信(オンデマンド配信のトリガー)
- Viewer切断時、Casterへ `{"type":"viewer-left"}` を送信
- Caster発の `{"type":"detection-status","hasCat":bool,"hasPerson":bool}` は、Viewerへ中継すると
  同時に直近の1件をRoom内にキャッシュする(下記「猫・人検知ステータスの確認」参照)
- それ以外の全メッセージ(offer/answer/ice-candidate等)は相手ロールへそのまま中継する

## 猫・人検知ステータスの確認(視聴を開始せずに)

`GET /room/<roomToken>/status`(`ACCESS_PASSWORD` 設定時は `?password=` も必須)で、
Casterが直近に報告した検知結果を確認できる。WebSocketアップグレードは不要な通常のGETで、
視聴用の接続(=Caster側のカメラ起動)を伴わない。

```json
{"hasCat": true, "hasPerson": false, "updatedAtMs": 1735689600000}
```

一度も報告が無い場合は `{"hasCat": null, "hasPerson": null, "updatedAtMs": null}` を返す。
Viewer(Cloudflare Pages)とはオリジンが異なるため、レスポンスに
`Access-Control-Allow-Origin: *` を付与している。詳細は
[docs/cat-person-detection.md](../docs/cat-person-detection.md) を参照。

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
