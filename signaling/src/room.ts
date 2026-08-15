export type Role = "caster" | "viewer";

interface Session {
  socket: WebSocket;
  role: Role;
}

/**
 * 1ルーム = 1視聴用トークンに対応するDurable Object。
 * Caster(配信アプリ)とViewer(視聴アプリ)のWebSocketを保持し、
 * Offer/Answer/ICE candidateをそのまま相手側へ中継する。
 */
export class Room {
  private sessions = new Map<WebSocket, Session>();

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
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
      this.relay(socket, event.data as string);
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
