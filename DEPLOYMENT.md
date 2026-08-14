# デプロイ手順(M4〜M5)

この開発環境にはCloudflareアカウントの認証情報・Android実機・Android SDKが無いため、
以下はユーザー環境で実施する。plan.md の M4(E2E結合)・M5(常設・デプロイ)に対応する。

## 1. シグナリングWorkerのデプロイ

```sh
cd signaling
npm install
npx wrangler login
npm run deploy
```

デプロイ後に発行されるURL(例: `https://tmn-signaling.<subdomain>.workers.dev`)を控える。

簡易パスワード認証(任意、README 1.4)を使う場合はここでシークレットを登録する:

```sh
npx wrangler secret put ACCESS_PASSWORD
```

## 2. ルームトークンの発行

推測困難な文字列(例: `openssl rand -hex 16`)を1つ生成し、視聴側・配信側の両方に設定する。

```sh
openssl rand -hex 16
```

## 3. 視聴Webアプリのデプロイ

`viewer/config.js` を編集し、1で控えたURLを `wss://` スキームで設定する。ACCESS_PASSWORDを設定した場合は `accessPassword` にも同じ値を設定する。

```js
window.TMN_CONFIG = {
  signalingUrl: "wss://tmn-signaling.<subdomain>.workers.dev",
  accessPassword: "",
};
```

Cloudflare Pagesにデプロイ:

```sh
cd viewer
npx wrangler pages deploy . --project-name=tmn-viewer
```

視聴用URL: `https://<pages-project>.pages.dev/index.html?room=<ルームトークン>`

## 4. 配信Androidアプリのビルド・設置

Android SDKが利用可能な環境(Android Studio等)で実施する。

1. `android/app/src/main/java/com/tmn/broadcaster/Config.kt` に `SIGNALING_URL` と `ROOM_TOKEN`(2で発行した値)を設定。ACCESS_PASSWORDを設定した場合は `ACCESS_PASSWORD` にも同じ値を設定
2. `./gradlew assembleDebug`(または Android Studio でビルド)
3. 配信用スマホにインストールし、カメラ権限を許可
4. バッテリー最適化除外・スリープなし設定を確認
5. 常時電源に接続した状態で設置

## 5. E2E確認(plan.md M4)

1. 視聴用URLにアクセスし、シグナリングWebSocketが接続されることを確認
2. 配信アプリ側で `viewer-joined` を受信し、カメラが起動・Offer送信されることを確認(Logcat)
3. 視聴側で映像が表示されることを確認
4. 自宅Wi-Fiと外出先回線(モバイル回線)の組み合わせでNAT越え・TURNフォールバックを確認
5. 視聴側切断 → 配信側が `viewer-left` を受けてカメラ・PeerConnectionを解放することを確認

## 6. 運用開始(plan.md M5)

- 数日runningさせ、発熱・電池持ち・再接続有無を一次観察
- TURN利用量をCloudflareダッシュボードで確認
