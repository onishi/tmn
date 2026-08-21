# 複数視聴者対応の設計(plan.md M6)

README 1.6「発展計画」・3章の判断基準表に対応する設計メモ。下記「A. P2P複数接続」の方針で
**実装済み**(`signaling/src/room.ts`・`android/.../StreamingService.kt`)。実機・複数ブラウザでの
同時接続は未検証(この開発環境にはAndroid SDKが無くビルド・実機確認ができないため)。

## 実装済みの構成(A. P2P複数接続)

- `Room`(Durable Object)が接続ごとに一意な`viewerId`(`crypto.randomUUID()`)を割り当てる
- `viewer-joined`/`viewer-left`に`viewerId`を含めてCasterへ通知する
- Caster発のoffer/ice-candidateには`viewerId`を含めることが必須で、Roomは該当Viewerのsocketのみに
  転送する(detection-statusは従来通りViewer全員へブロードキャスト)
- Viewer発のanswer/ice-candidateも自身の`viewerId`を含めて送信し、Caster側で対応する
  `PeerConnection`を引くのに使う
- `StreamingService.kt`は`peerConnections: MutableMap<String, PeerConnection>`(viewerId→PeerConnection)を
  保持し、視聴者ごとに個別のPeerConnectionを確立・破棄する。カメラ・映像トラックは全視聴者で共有し、
  最初の視聴者接続時にのみ起動、最後の視聴者切断時に停止する(前述の通りエンコード自体は接続ごとに
  走るため、CPU負荷は視聴者数に比例する点は変わらない)
- 一定時間(20秒)内にICE接続がCONNECTED/COMPLETEDへ進まない視聴者は自動的に切断する
  (ブラウザタブが正常なclose通知を送らずに閉じた場合などに"new"のまま残り続けるのを防ぐ)

視聴者数が実際に増えてボトルネックになった段階で、下記「B. SFU移行」を検討する方針は変わらない。

## 実装前(1対1)の制約だった内容(参考・経緯)

以前の `signaling/src/room.ts` は「役割(`caster`/`viewer`)」単位でメッセージを中継しており、
`relay()` は送信者と逆の役割を持つ**全セッション**にそのまま転送していた:

```ts
const targetRole: Role = senderSession.role === "caster" ? "viewer" : "caster";
for (const session of this.sessions.values()) {
  if (session.role === targetRole) session.socket.send(data);
}
```

これは1Viewerを前提にした実装で、2人目のViewerが接続すると以下の問題が起きる:

- WebRTCのOffer/AnswerはPeerConnection単位(1対1)の交渉であり、1つのOfferを複数Viewerにブロードキャストできない
- 現在の `StreamingService.kt` は `peerConnection: PeerConnection?` を1つしか保持せず、2人目の
  `viewer-joined` を受けると(既存の再接続対応により)既存のPeerConnectionを破棄して新しいOfferを
  ブロードキャストしてしまう。結果、両方のViewerが同じOfferを受け取り、両方がAnswerを返すが
  Caster側はどちらのAnswerかを区別できず、SDPネゴシエーションが壊れる
- ICE candidateも同様に、宛先Viewerを区別する仕組みがない

## 比較: P2P複数接続 vs SFU移行

| 観点 | A. P2P複数接続(Caster側で複数PeerConnection) | B. Cloudflare Realtime SFU移行 |
|---|---|---|
| アーキテクチャ変更 | シグナリングにViewer識別子を追加する程度。現行のCloudflare Workers構成のまま拡張可能 | Casterの送信先をSFUに変更し、シグナリングモデル自体を作り直す必要がある(セッション/トラックベース) |
| Casterの負荷(配信用スマホのCPU/バッテリー) | Viewer数だけエンコードが増える(WebRTCは同じVideoTrackを複数PeerConnectionで送っても、送信側では接続ごとに個別にエンコードが走る)。CPU/バッテリー/発熱の面で古いスマホには厳しい | エンコードは1回のみ(Caster→SFU)。SFUがViewer数分の配信を肩代わりするためCasterの負荷は増えない |
| 実装コスト(現時点) | 低〜中。シグナリングにViewer IDを足す程度 | 中〜高。Cloudflare Realtime SFUのAPI・セッションモデルを新たに学習・統合する必要がある |
| 向いている規模 | 視聴者2〜3人程度 | 視聴者5人以上、または常時録画・複数カメラなど拡張を見据える場合 |

→ README 3章の判断基準表(「視聴者が2〜3人に増えた→P2Pの複数接続で対応可能」「5人以上→SFU導入を検討」)
と一致する。**まずはA(P2P複数接続)を実装し、視聴者数が実際に増えてボトルネックになった段階でB(SFU)へ
移行するのが妥当**と判断する。

## 実装済みの設計方針(要約)

上記「実装済みの構成」で述べた通り、実装は当初の設計方針(シグナリングに`viewerId`を追加、
Caster側は`viewerId`→`PeerConnection`のMapで管理、Viewer側は変更不要)にほぼ沿って行われた。
`signaling/test/manual-relay-check.mjs`は複数Viewer接続時の宛先分離を確認する範囲までは
拡張していない(Playwright E2Eの範囲で単一Viewerの疎通のみ確認済み)。後方互換は考慮していない
(Viewer・Casterとも同一リポジトリ内で同時にプロトコルを更新したため)。
