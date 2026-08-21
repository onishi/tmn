import { Room } from "./room";

export { Room };

export interface Env {
  ROOMS: DurableObjectNamespace;
  // 設定されていれば ?password= の一致を要求する(簡易パスワード認証、README 1.4 / plan.md M6)。
  // 未設定の場合はルームトークンのみで従来通り動作する。
  ACCESS_PASSWORD?: string;
  // Cloudflare Calls(Realtime)のTURN App ID / Auth Token。
  // 同一ネットワーク内でもルーターがNATヘアピンに対応していないとSTUNのみでは
  // P2P接続が確立できないことがあるため、TURN中継のフォールバックとして使用する。
  TURN_APP_ID?: string;
  TURN_APP_TOKEN?: string;
}

// 視聴用URLの ?room=xxxxxxxx トークンをそのままルームパスに用いる。
// トークンが分からない限り該当ルームの接続情報は読み書きできない(README 1.4)。
const ROOM_PATH = /^\/room\/([A-Za-z0-9_-]{8,})$/;

// 視聴を開始せずに、直近の猫・人検知結果だけを確認するための読み取り専用エンドポイント。
// WebSocketアップグレードは不要な通常のGETリクエスト。
const ROOM_STATUS_PATH = /^\/room\/([A-Za-z0-9_-]{8,})\/status$/;

// Caster・Viewer共通で使う、一時的なTURN認証情報を発行するエンドポイント。
// TURN_APP_TOKEN(シークレット)はここでのみ使用し、クライアントには短命の
// username/credentialペアだけを渡す。
const TURN_CREDENTIALS_PATH = /^\/turn-credentials$/;

/** 文字列長に関わらず常に同じ回数だけ比較する簡易的なタイミングセーフ比較 */
function timingSafeEqual(a: string, b: string): boolean {
  const encoder = new TextEncoder();
  const bufA = encoder.encode(a);
  const bufB = encoder.encode(b);
  const length = Math.max(bufA.length, bufB.length);

  let diff = bufA.length ^ bufB.length;
  for (let i = 0; i < length; i++) {
    diff |= (bufA[i] ?? 0) ^ (bufB[i] ?? 0);
  }
  return diff === 0;
}

/** ACCESS_PASSWORDが設定されている場合のみ、?password= の一致を確認する */
function checkPassword(url: URL, env: Env): Response | null {
  if (!env.ACCESS_PASSWORD) return null;
  const password = url.searchParams.get("password") ?? "";
  if (!timingSafeEqual(password, env.ACCESS_PASSWORD)) {
    return new Response("Unauthorized", { status: 401 });
  }
  return null;
}

const CORS_HEADERS = { "access-control-allow-origin": "*" };

/**
 * Cloudflare Calls(Realtime)のTURN Key APIから短命のTURN認証情報を取得して返す。
 * TURN_APP_ID/TURN_APP_TOKENが未設定の環境(TURN未対応)では iceServers: null を返し、
 * クライアント側はSTUNのみにフォールバックする。
 */
async function handleTurnCredentials(env: Env): Promise<Response> {
  if (!env.TURN_APP_ID || !env.TURN_APP_TOKEN) {
    return new Response(JSON.stringify({ iceServers: null }), {
      headers: { "content-type": "application/json", ...CORS_HEADERS },
    });
  }

  const res = await fetch(
    `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_APP_ID}/credentials/generate`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${env.TURN_APP_TOKEN}`,
        "content-type": "application/json",
      },
      // 24時間。配信の都度取得し直すため短くする必要はない
      body: JSON.stringify({ ttl: 86400 }),
    },
  );

  if (!res.ok) {
    return new Response(JSON.stringify({ iceServers: null }), {
      status: 502,
      headers: { "content-type": "application/json", ...CORS_HEADERS },
    });
  }

  const data = (await res.json()) as { iceServers: { urls: string[]; username: string; credential: string } };
  // ポート53はChrome/FirefoxがWebRTC用途でブロックするため、ブラウザ・Android問わず除外する
  data.iceServers.urls = data.iceServers.urls.filter((u) => !u.includes(":53"));

  return new Response(JSON.stringify(data), {
    headers: { "content-type": "application/json", ...CORS_HEADERS },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    const statusMatch = url.pathname.match(ROOM_STATUS_PATH);
    if (statusMatch) {
      const denied = checkPassword(url, env);
      if (denied) return denied;

      const id = env.ROOMS.idFromName(statusMatch[1]);
      return env.ROOMS.get(id).fetch(request);
    }

    if (TURN_CREDENTIALS_PATH.test(url.pathname)) {
      const denied = checkPassword(url, env);
      if (denied) return denied;

      return handleTurnCredentials(env);
    }

    const match = url.pathname.match(ROOM_PATH);
    if (!match) {
      return new Response("Not found", { status: 404 });
    }

    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected websocket", { status: 426 });
    }

    const denied = checkPassword(url, env);
    if (denied) return denied;

    const roomToken = match[1];
    const id = env.ROOMS.idFromName(roomToken);
    const stub = env.ROOMS.get(id);
    return stub.fetch(request);
  },
};
