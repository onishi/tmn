# Android 14〜17対応の検討(配信Androidアプリ)

配信Androidアプリ(`android/`)をAndroid 14〜17の端末で動作させるために調査した内容と、
採用した方針の記録。この開発環境にはAndroid SDKが無くビルド確認ができないため、
判断の根拠を残しておく。

## バージョン対応表

| Android バージョン | API Level |
|---|---|
| Android 14 | 34 |
| Android 15 | 35 |
| Android 16 | 36 |
| Android 17 | 37(2026年6月リリース、コードネーム Cinnamon Bun) |

## 採用した方針: targetSdk 36(Android 16)まで

- `compileSdk` / `targetSdk` を34→36に引き上げた
- Android Gradle Plugin(AGP)は8.5.2→8.10.0に更新(AGP 8.xの範囲でcompileSdk 36まで対応可能。
  AGP 9.x系はKotlin Gradle Pluginの扱いが変わるなど破壊的変更を伴う大きな移行のため、
  この開発環境でビルド確認できない状況で踏み込むのは避けた)
- Gradle wrapperは既に8.14.3で、AGP 8.10.0の最低要件(8.11.1)を満たすため変更不要

targetSdkを36に留めても、**Android 17端末上でアプリが動作しなくなるわけではない**。
Androidは後方互換を保証しており、targetSdkが低いアプリは新しい挙動を「オプトインしていない」
扱いで従来通り動作する。Google Playの提出要件(targetSdk 37への追従期限)は本アプリのような
サイドロード配布では関係しない。

## Android 17(API 37)へtargetSdkを上げなかった理由

Android 17で`ACCESS_LOCAL_NETWORK`という新しい実行時権限が導入され、
**targetSdk 37以上のアプリにのみ**強制される(Android 16では任意オプトイン扱い)。

- 同一Wi-Fi内のソケット通信・mDNS探索・生ソケット接続全般が対象になり、権限が無い状態では
  「接続が単に発生しない」(ソケットエラー)という形で失敗する
- WebRTCのICE候補収集は、ローカルネットワークインターフェースのIPアドレスを列挙して
  「host candidate」(直接P2P接続用)を作る処理を含む。これが上記の制限に該当する可能性が高い
- 本プロジェクトが使っている非公式コミュニティ版WebRTC SDK
  (`io.github.webrtc-sdk:android:125.6422.07.1`)が、この新しい権限モデルを認識して
  適切にフォールバック(権限が無ければTURN経由にのみ絞る等)するかどうかは未確認
- 誤って対応すると、自宅Wi-Fi内での直接P2P接続(TURNを介さない最も低遅延な経路)が
  静かに壊れるリスクがあり、この開発環境ではビルド・実機検証ができないため、
  リスクを取ってtargetSdk 37まで踏み込むのは見送った

## 将来targetSdk 37に上げる場合にやること

- `AndroidManifest.xml`に`ACCESS_LOCAL_NETWORK`権限を追加
- `MainActivity`でのランタイム権限リクエストに追加(カメラ・通知と同様)
- 権限が拒否された場合にWebRTC側がSTUN/TURN経由の候補のみで動作を継続できるか実機で確認
- 使用しているWebRTC SDKのバージョンが新しい権限モデルに対応しているか確認(必要なら
  SDKアップデート)
- AGP 9.x系への移行(Kotlin Gradle Pluginの扱いの変更を含む)
- 実機(Android 17端末)でのE2E確認が必須。この開発環境では検証できない

## 参考: 調査で確認したその他の挙動変化

- **Android 15(API 35)**: targetSdk 35以上でedge-to-edgeがデフォルト化。本アプリの
  `MainActivity`は`setContentView`を呼ばない(権限リクエストのみの一時的な画面)ため、
  レイアウト崩れの影響は無いと判断
- **Android 16(API 36)**: `targetSdkVersion`に関わらず、Foreground Serviceからの
  バックグラウンドジョブ(WorkManager等)にもクォータが厳格に適用されるようになった。
  本アプリは現時点でWorkManagerを実際には使っていない(依存関係のみ追加済み、
  plan.md M6の「プロセス生存確認」向けに温存)ため、現状は影響なし
- **Foreground Service のカメラ種別権限**(`FOREGROUND_SERVICE_CAMERA`)は
  API 34以上で必須。既に対応済み(別PR)
