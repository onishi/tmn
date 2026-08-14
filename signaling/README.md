# tmn-signaling

Cloudflare Workers + Durable Objects によるWebRTCシグナリング中継。
ルームトークンごとに1つの `Room` Durable Objectが割り当てられ、broadcaster(配信Androidアプリ)と
viewer(視聴Webアプリ)間のOffer/Answer/ICE candidateを中継する。

## プロトコル

`wss://<host>/room/<roomToken>?role=broadcaster|viewer` に接続する。

- viewer接続時、broadcasterへ `{"type":"viewer-joined"}` を送信(オンデマンド配信のトリガー)
- viewer切断時、broadcasterへ `{"type":"viewer-left"}` を送信
- それ以外の全メッセージ(offer/answer/ice-candidate等)は相手ロールへそのまま中継する

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

## デプロイ

```sh
npx wrangler login
npm run deploy
```
