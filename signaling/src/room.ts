export type Role = "caster" | "viewer";

interface Session {
  socket: WebSocket;
  role: Role;
}

interface DetectionStatus {
  hasCat: boolean;
  hasPerson: boolean;
  updatedAtMs: number;
}

/**
 * 1ルーム = 1視聴用トークンに対応するDurable Object。
 * Caster(配信アプリ)とViewer(視聴アプリ)のWebSocketを保持し、
 * Offer/Answer/ICE candidateをそのまま相手側へ中継する。
 * Casterから送られてくる猫・人検知結果(detection-status)は直近の1件をキャッシュし、
 * 視聴を開始する前でも `GET /room/<token>/status` から確認できるようにする。
 */
export class Room {
  private sessions = new Map<WebSocket, Session>();
  private lastDetectionStatus: DetectionStatus | null = null;

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname.endsWith("/status")) {
      return this.handleStatusRequest();
    }

    const role = url.searchParams.get("role");

    if (role !== "caster" && role !== "viewer") {
      return new Response("role must be 'caster' or 'viewer'", { status: 400 });
    }

    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected websocket", { status: 426 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    this.handleSession(server, role);

    return new Response(null, { status: 101, webSocket: client });
  }

  /** 視聴を開始せずに、直近の猫・人検知結果だけを確認するための読み取り専用エンドポイント */
  private handleStatusRequest(): Response {
    const body = this.lastDetectionStatus ?? { hasCat: null, hasPerson: null, updatedAtMs: null };
    return new Response(JSON.stringify(body), {
      headers: {
        "content-type": "application/json",
        // Viewer(Cloudflare Pages)とシグナリングWorkerは別オリジンなので、
        // ブラウザからのfetch()がCORSで拒否されないようにする。
        // room/passwordを知らなければ意味のある情報は得られない読み取り専用エンドポイントのため、
        // オリジンを制限する必要はない
        "access-control-allow-origin": "*",
      },
    });
  }

  private handleSession(socket: WebSocket, role: Role): void {
    socket.accept();
    this.sessions.set(socket, { socket, role });

    if (role === "viewer") {
      this.sendToRole("caster", { type: "viewer-joined" });
    } else if (this.hasRole("viewer")) {
      // Casterがviewer接続後に(再)接続してきた場合も通知する
      socket.send(JSON.stringify({ type: "viewer-joined" }));
    }

    socket.addEventListener("message", (event: MessageEvent) => {
      const data = event.data as string;
      if (role === "caster") {
        this.tryCacheDetectionStatus(data);
      }
      this.relay(socket, data);
    });

    const cleanup = () => {
      const session = this.sessions.get(socket);
      this.sessions.delete(socket);
      if (session?.role === "viewer") {
        this.sendToRole("caster", { type: "viewer-left" });
      }
    };

    socket.addEventListener("close", cleanup);
    socket.addEventListener("error", cleanup);
  }

  /** Caster発のメッセージはviewer全員へ、viewer発のメッセージはCasterへ中継する */
  private relay(sender: WebSocket, data: string): void {
    const senderSession = this.sessions.get(sender);
    if (!senderSession) return;

    const targetRole: Role = senderSession.role === "caster" ? "viewer" : "caster";
    for (const session of this.sessions.values()) {
      if (session.role === targetRole) {
        session.socket.send(data);
      }
    }
  }

  /** Caster発のメッセージが detection-status であれば、直近の1件として保持する */
  private tryCacheDetectionStatus(data: string): void {
    let message: unknown;
    try {
      message = JSON.parse(data);
    } catch {
      return;
    }

    if (
      typeof message !== "object" ||
      message === null ||
      (message as Record<string, unknown>).type !== "detection-status"
    ) {
      return;
    }

    const payload = message as Record<string, unknown>;
    this.lastDetectionStatus = {
      hasCat: Boolean(payload.hasCat),
      hasPerson: Boolean(payload.hasPerson),
      updatedAtMs: Date.now(),
    };
  }

  private hasRole(role: Role): boolean {
    for (const session of this.sessions.values()) {
      if (session.role === role) return true;
    }
    return false;
  }

  private sendToRole(role: Role, message: unknown): void {
    const payload = JSON.stringify(message);
    for (const session of this.sessions.values()) {
      if (session.role === role) {
        session.socket.send(payload);
      }
    }
  }
}
