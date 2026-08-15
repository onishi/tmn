# 複数視聴者対応の設計検討(plan.md M6)

README 1.6「発展計画」・3章の判断基準表に対応する設計メモ。現時点では**設計検討のみ**で実装はしない
(視聴者は1人という前提のMVPをまず安定運用させることを優先するため)。実装に着手する際の設計方針として
残す。

## 現状(1対1)の制約

現在の `signaling/src/room.ts` は「役割(broadcaster/viewer)」単位でメッセージを中継しており、
`relay()` は送信者と逆の役割を持つ**全セッション**にそのまま転送する:

```ts
const targetRole: Role = senderSession.role === "broadcaster" ? "viewer" : "broadcaster";
for (const session of this.sessions.values()) {
  if (session.role === targetRole) session.socket.send(data);
}
```

これは1viewerを前提にした実装で、2人目のviewerが接続すると以下の問題が起きる:

- WebRTCのOffer/AnswerはPeerConnection単位(1対1)の交渉であり、1つのOfferを複数viewerにブロードキャストできない
- 現在の `StreamingService.kt` は `peerConnection: PeerConnection?` を1つしか保持せず、2人目の
  `viewer-joined` を受けると(既存の再接続対応により)既存のPeerConnectionを破棄して新しいOfferを
  ブロードキャストしてしまう。結果、両方のviewerが同じOfferを受け取り、両方がAnswerを返すが
  broadcaster側はどちらのAnswerかを区別できず、SDPネゴシエーションが壊れる
- ICE candidateも同様に、宛先viewerを区別する仕組みがない

## 比較: P2P複数接続 vs SFU移行

| 観点 | A. P2P複数接続(broadcaster側で複数PeerConnection) | B. Cloudflare Realtime SFU移行 |
|---|---|---|
| アーキテクチャ変更 | シグナリングにviewer識別子を追加する程度。現行のCloudflare Workers構成のまま拡張可能 | broadcasterの送信先をSFUに変更し、シグナリングモデル自体を作り直す必要がある(セッション/トラックベース) |
| 配信スマホの負荷 | viewer数だけエンコードが増える(WebRTCは同じVideoTrackを複数PeerConnectionで送っても、送信側では接続ごとに個別にエンコードが走る)。CPU/バッテリー/発熱の面で古いスマホには厳しい | エンコードは1回のみ(broadcaster→SFU)。SFUがviewer数分の配信を肩代わりするため配信スマホの負荷は増えない |
| 実装コスト(現時点) | 低〜中。シグナリングにviewer IDを足す程度 | 中〜高。Cloudflare Realtime SFUのAPI・セッションモデルを新たに学習・統合する必要がある |
| 向いている規模 | 視聴者2〜3人程度 | 視聴者5人以上、または常時録画・複数カメラなど拡張を見据える場合 |

→ README 3章の判断基準表(「視聴者が2〜3人に増えた→P2Pの複数接続で対応可能」「5人以上→SFU導入を検討」)
と一致する。**まずはA(P2P複数接続)を実装し、視聴者数が実際に増えてボトルネックになった段階でB(SFU)へ
移行するのが妥当**と判断する。

## A. P2P複数接続を実装する場合の設計方針

実装時のメモとして残す(現時点では未着手)。

### シグナリング(signaling/)

- `Room` のセッションに一意な `viewerId`(接続時にDurable Object側で生成、例: `crypto.randomUUID()`)を付与する
- `viewer-joined` / `viewer-left` に `viewerId` を含めてbroadcasterへ通知する
  - 例: `{"type":"viewer-joined","viewerId":"..."}`
- broadcaster発のoffer/ice-candidateにも `viewerId` を含めることを必須にし、Roomは該当viewerのsocketのみに転送する(現在の「role全員に送る」実装から「特定のviewerId宛てに送る」実装へ変更)
- viewer発のanswer/ice-candidateは自身の `viewerId` を含めて送信し、broadcaster側で対応するPeerConnectionを引くのに使う

### 配信Androidアプリ(android/)

- `StreamingService.kt` の `peerConnection: PeerConnection?` を `MutableMap<String, PeerConnection>`(viewerId→PeerConnection)に変更
- `videoSource` / `videoTrack` はviewer間で共有可能(同じキャプチャを複数のPeerConnectionの送信トラックとして使い回せる)。ただし前述の通りエンコード自体は接続ごとに走るため、CPU負荷はviewer数に比例する点に注意
- `viewer-joined` → 該当viewerId用に新しいPeerConnectionを作成しOfferを送る、`viewer-left` → 該当viewerIdのPeerConnectionのみ破棄(他のviewerには影響させない)

### 視聴Webアプリ(viewer/)

- 現状の実装(1viewer=1PeerConnection)のままで変更不要。Room側がviewerIdで宛先を絞ってくれるようになれば、viewer側は今まで通りoffer/answer/ice-candidateをやり取りするだけでよい

### 移行時の注意点

- ロールごとのブロードキャストからviewerId宛て配送に変わるため、`signaling/test/manual-relay-check.mjs` は複数viewer接続時の宛先分離を検証するテストに拡張する必要がある
- 後方互換は不要(viewer/androidとも同一リポジトリ内で同時にプロトコルを更新するため)
