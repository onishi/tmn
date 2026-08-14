# tmn-viewer

視聴用の静的Webページ。ビルド不要(vanilla HTML/JS)。Cloudflare Pagesでホスティングする。

## 使い方

`https://<デプロイ先>/index.html?room=<ルームトークン>` にアクセスすると、`config.js` に設定した
シグナリングWorker経由でWebRTC接続し、配信映像を表示する。

## 開発前の設定

`config.js` の `signalingUrl` をデプロイ済みのシグナリングWorkerのURLに書き換える。

```js
window.TMN_CONFIG = {
  signalingUrl: "wss://tmn-signaling.<your-subdomain>.workers.dev",
};
```

## ローカル動作確認

```sh
python3 -m http.server 5500
```

`../signaling` を `npm run dev` でローカル起動し、`config.js` の `signalingUrl` を
`ws://localhost:8787` に一時的に変更すれば、`test/broadcaster-mock.html`
(getUserMediaで模擬カメラ映像を配信するテスト専用ページ)と組み合わせてWebRTC疎通を確認できる。

```
http://localhost:5500/index.html?room=test-room-token-1234
http://localhost:5500/test/broadcaster-mock.html?room=test-room-token-1234&signaling=ws://localhost:8787
```
