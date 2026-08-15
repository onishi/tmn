import { Room } from "./room";

export { Room };

export interface Env {
  ROOMS: DurableObjectNamespace;
  // 設定されていれば ?password= の一致を要求する(簡易パスワード認証、README 1.4 / plan.md M6)。
  // 未設定の場合はルームトークンのみで従来通り動作する。
  ACCESS_PASSWORD?: string;
}

// 視聴用URLの ?room=xxxxxxxx トークンをそのままルームパスに用いる。
// トークンが分からない限り該当ルームの接続情報は読み書きできない(README 1.4)。
const ROOM_PATH = /^\/room\/([A-Za-z0-9_-]{8,})$/;

// 視聴を開始せずに、直近の猫・人検知結果だけを確認するための読み取り専用エンドポイント。
// WebSocketアップグレードは不要な通常のGETリクエスト。
const ROOM_STATUS_PATH = /^\/room\/([A-Za-z0-9_-]{8,})\/status$/;

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
