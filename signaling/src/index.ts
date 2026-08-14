import { Room } from "./room";

export { Room };

export interface Env {
  ROOMS: DurableObjectNamespace;
}

// 視聴用URLの ?room=xxxxxxxx トークンをそのままルームパスに用いる。
// トークンが分からない限り該当ルームの接続情報は読み書きできない(README 1.4)。
const ROOM_PATH = /^\/room\/([A-Za-z0-9_-]{8,})$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const match = url.pathname.match(ROOM_PATH);

    if (!match) {
      return new Response("Not found", { status: 404 });
    }

    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected websocket", { status: 426 });
    }

    const roomToken = match[1];
    const id = env.ROOMS.idFromName(roomToken);
    const stub = env.ROOMS.get(id);
    return stub.fetch(request);
  },
};
