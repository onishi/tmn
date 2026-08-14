package com.tmn.broadcaster

// デプロイ環境ごとに書き換えること。ROOM_TOKENは視聴側(viewer)のURL ?room=<token> と一致させる。
object Config {
    const val SIGNALING_URL = "wss://tmn-signaling.<your-subdomain>.workers.dev"
    const val ROOM_TOKEN = "<room-token>"
}
