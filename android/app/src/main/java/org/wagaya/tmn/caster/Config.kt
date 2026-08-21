package org.wagaya.tmn.caster

// デプロイ環境ごとに書き換えること。ROOM_TOKENは視聴側(viewer)のURL ?room=<token> と一致させる。
object Config {
    const val SIGNALING_URL = "wss://tmn-signaling.wagaya.workers.dev"
    const val ROOM_TOKEN = "5d5aa5c55353a7240cf05a667ed0381f"

    // シグナリングWorker側で ACCESS_PASSWORD を設定している場合のみ、同じ値を設定する(任意)
    const val ACCESS_PASSWORD = ""
}
