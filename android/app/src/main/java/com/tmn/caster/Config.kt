package com.tmn.caster

// デプロイ環境ごとに書き換えること。ROOM_TOKENは視聴側(viewer)のURL ?room=<token> と一致させる。
object Config {
    const val SIGNALING_URL = "wss://tmn-signaling.<your-subdomain>.workers.dev"
    const val ROOM_TOKEN = "<room-token>"

    // シグナリングWorker側で ACCESS_PASSWORD を設定している場合のみ、同じ値を設定する(任意)
    const val ACCESS_PASSWORD = ""
}
