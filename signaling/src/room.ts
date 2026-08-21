export type Role = "caster" | "viewer";

interface Session {
  socket: WebSocket;
  role: Role;
  id: string;
  browserName: string;
  // 最後にメッセージ(pingへの応答含む)を受信した時刻。生存確認に使う
  lastMessageAtMs: number;
}

// 視聴者セッションの生存確認のためpingを送る間隔
const HEARTBEAT_INTERVAL_MS = 20_000;
// この時間メッセージ(pingへのpong応答含む)が来なければ切断されたとみなす。
// スマホのバックグラウンド化・回線切断などでWebSocketのcloseイベントが
// 届かないまま接続だけが残るケースがあり、Caster側の視聴者一覧と
// Viewer側の視聴者数表示がずれる原因になっていたため、能動的に生存確認する
const HEARTBEAT_TIMEOUT_MS = 45_000;

/** User-Agentから簡易的にブラウザ名を判定する(Caster側の視聴者一覧に表示するための表示用情報) */
function parseBrowserName(userAgent: string | null): string {
  if (!userAgent) return "不明";
  if (/Edg\//.test(userAgent)) return "Edge";
  if (/OPR\//.test(userAgent) || /Opera/.test(userAgent)) return "Opera";
  if (/FxiOS\//.test(userAgent)) return "Firefox(iOS)";
  if (/Firefox\//.test(userAgent)) return "Firefox";
  if (/CriOS\//.test(userAgent)) return "Chrome(iOS)";
  if (/Chrome\//.test(userAgent)) return "Chrome";
  if (/Safari\//.test(userAgent) && /Version\//.test(userAgent)) return "Safari";
  if (/Android/.test(userAgent)) return "Android";
  return "不明";
}

interface DetectionStatus {
  hasAnimal: boolean;
  hasPerson: boolean;
  updatedAtMs: number;
}

/**
 * 1ルーム = 1視聴用トークンに対応するDurable Object。
 * Caster(配信アプリ)とViewer(視聴アプリ)のWebSocketを保持し、
 * Offer/Answer/ICE candidateをそのまま相手側へ中継する。
 * 視聴者ごとに一意なviewerIdを割り当て、Caster側が視聴者ごとに個別の
 * PeerConnectionを確立できるようにする(複数視聴者の同時アクセスに対応するため)。
 * Casterから送られてくる動物・人検知結果(detection-status)は直近の1件をキャッシュし、
 * 視聴を開始する前でも `GET /room/<token>/status` から確認できるようにする。
 */
export class Room {
  private sessions = new Map<WebSocket, Session>();
  private lastDetectionStatus: DetectionStatus | null = null;

  constructor() {
    setInterval(() => this.sweepStaleViewers(), HEARTBEAT_INTERVAL_MS);
  }

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

    this.handleSession(server, role, request.headers.get("User-Agent"));

    return new Response(null, { status: 101, webSocket: client });
  }

  /**
   * 視聴(WebSocket接続)を開始せずに、直近の動物・人検知結果と現在の視聴者数だけを
   * 確認するための読み取り専用エンドポイント
   */
  private handleStatusRequest(): Response {
    const detection = this.lastDetectionStatus ?? { hasAnimal: null, hasPerson: null, updatedAtMs: null };
    const body = { ...detection, viewerCount: this.countViewers() };
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

  private handleSession(socket: WebSocket, role: Role, userAgent: string | null): void {
    socket.accept();
    const id = crypto.randomUUID();
    const browserName = parseBrowserName(userAgent);
    this.sessions.set(socket, { socket, role, id, browserName, lastMessageAtMs: Date.now() });

    if (role === "viewer") {
      // 自分自身のviewerIdを知らせる。以降、answer/ice-candidateにこのIDを添えて送り返してもらう
      socket.send(JSON.stringify({ type: "welcome", viewerId: id }));
      this.sendToRole("caster", { type: "viewer-joined", viewerId: id, browserName });
      this.broadcastViewerCount();
    } else {
      // Casterが(再)接続してきた場合、その時点で既に接続中の視聴者全員分の
      // viewer-joinedを通知する(視聴者ごとに個別のPeerConnectionを張り直してもらうため)
      for (const session of this.sessions.values()) {
        if (session.role === "viewer") {
          socket.send(
            JSON.stringify({ type: "viewer-joined", viewerId: session.id, browserName: session.browserName })
          );
        }
      }
    }

    socket.addEventListener("message", (event: MessageEvent) => {
      const data = event.data as string;
      const session = this.sessions.get(socket);
      if (session) session.lastMessageAtMs = Date.now();
      if (this.isPong(data)) return; // 生存確認の応答は中継しない
      if (role === "caster") {
        this.tryCacheDetectionStatus(data);
      }
      this.relay(socket, data);
    });

    const cleanup = () => {
      const session = this.sessions.get(socket);
      this.sessions.delete(socket);
      if (session?.role === "viewer") {
        this.sendToRole("caster", { type: "viewer-left", viewerId: session.id });
        this.broadcastViewerCount();
      }
    };

    socket.addEventListener("close", cleanup);
    socket.addEventListener("error", cleanup);
  }

  /**
   * Viewer発のメッセージ(answer/ice-candidate)はそのままCasterへ中継する
   * (Caster側がメッセージ内のviewerIdを見て、対応するPeerConnectionへ振り分ける)。
   * Caster発のメッセージのうち detection-status は視聴者全員へブロードキャストし、
   * それ以外(offer/ice-candidate)はメッセージ内のviewerIdで指定された視聴者1人にだけ中継する。
   */
  private relay(sender: WebSocket, data: string): void {
    const senderSession = this.sessions.get(sender);
    if (!senderSession) return;

    if (senderSession.role === "viewer") {
      this.sendToRole("caster", data);
      return;
    }

    let message: unknown;
    try {
      message = JSON.parse(data);
    } catch {
      return;
    }
    if (typeof message !== "object" || message === null) return;
    const payload = message as Record<string, unknown>;

    if (payload.type === "detection-status") {
      this.sendToRole("viewer", data);
      return;
    }

    const targetViewerId = payload.viewerId;
    if (typeof targetViewerId !== "string") return;
    for (const session of this.sessions.values()) {
      if (session.role === "viewer" && session.id === targetViewerId) {
        session.socket.send(data);
        return;
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
      hasAnimal: Boolean(payload.hasAnimal),
      hasPerson: Boolean(payload.hasPerson),
      updatedAtMs: Date.now(),
    };
  }

  private isPong(data: string): boolean {
    try {
      return (JSON.parse(data) as Record<string, unknown>)?.type === "pong";
    } catch {
      return false;
    }
  }

  /**
   * 視聴者セッションの生存確認。スマホのバックグラウンド化・回線切断などで
   * WebSocketのcloseイベントが届かないまま接続だけが残ることがあり、
   * Caster側の視聴者一覧とViewer側の視聴者数表示がずれる原因になっていたため、
   * 一定間隔でpingを送り、応答がないセッションは強制的に閉じる
   * (closeイベントハンドラ側でsessions・視聴者数は通常通り更新される)
   */
  private sweepStaleViewers(): void {
    const now = Date.now();
    for (const [socket, session] of this.sessions) {
      if (session.role !== "viewer") continue;
      if (now - session.lastMessageAtMs > HEARTBEAT_TIMEOUT_MS) {
        try {
          socket.close();
        } catch {
          // ignore: 既に切れている場合など
        }
        continue;
      }
      try {
        socket.send(JSON.stringify({ type: "ping" }));
      } catch {
        // ignore: 送信に失敗する場合は次回のタイムアウト判定で拾われる
      }
    }
  }

  /** 現在の視聴者数を全視聴者へ通知する(視聴者の入退室のたびに呼ぶ) */
  private broadcastViewerCount(): void {
    this.sendToRole("viewer", { type: "viewer-count", count: this.countViewers() });
  }

  private countViewers(): number {
    let count = 0;
    for (const session of this.sessions.values()) {
      if (session.role === "viewer") count++;
    }
    return count;
  }

  /** 指定ロールの全セッションへメッセージを送る。データが文字列ならそのまま、それ以外はJSON化する */
  private sendToRole(role: Role, message: unknown): void {
    const payload = typeof message === "string" ? message : JSON.stringify(message);
    for (const session of this.sessions.values()) {
      if (session.role === role) {
        session.socket.send(payload);
      }
    }
  }
}
