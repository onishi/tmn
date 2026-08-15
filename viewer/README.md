# tmn-viewer

視聴用の静的Webページ。ビルド不要(vanilla HTML/JS)。Cloudflare Pagesでホスティングする。

## 使い方

`https://<デプロイ先>/index.html?room=<ルームトークン>` にアクセスすると、`config.js` に設定した
シグナリングWorker経由でWebRTC接続し、配信映像を表示する。

スマホでの視聴を主な用途として想定しており、以下のモバイル対応を行っている:

- `100dvh`(動的ビューポート高さ)を優先使用し、iOS Safari等でアドレスバーの出没によって
  レイアウトが跳ねるのを防ぐ(未対応ブラウザでは`100vh`にフォールバック)
- ノッチ・ホームインジケーター領域を`env(safe-area-inset-*)`で避ける
- 映像は`object-fit: contain`で、画面比率が違っても欠けずに全体表示
- 自動再生の安定性のため`<video>`に`muted`を付与(現状音声トラックは無いため実害なし)
- 全画面ボタンを用意(Fullscreen API、非対応のiOS Safariは`webkitEnterFullscreen`にフォールバック)

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
