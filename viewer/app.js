const params = new URLSearchParams(location.search);
const room = params.get("room");
const statusEl = document.getElementById("status");
const videoEl = document.getElementById("remote-video");

const INITIAL_RECONNECT_DELAY_MS = 1000;
const MAX_RECONNECT_DELAY_MS = 15000;

let reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
let reconnectTimer = null;
let currentPc = null;

function setStatus(text) {
  statusEl.textContent = text;
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  setStatus(`接続が切断されました。${Math.round(reconnectDelay / 1000)}秒後に再接続します...`);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, reconnectDelay);
  reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS);
}

function teardownPeerConnection() {
  if (currentPc) {
    currentPc.close();
    currentPc = null;
  }
  videoEl.srcObject = null;
}

function connect() {
  if (!room) {
    setStatus("URLに ?room=<トークン> が必要です");
    return;
  }

  const { signalingUrl, accessPassword } = window.TMN_CONFIG;
  const passwordQuery = accessPassword ? `&password=${encodeURIComponent(accessPassword)}` : "";
  const wsUrl = `${signalingUrl}/room/${encodeURIComponent(room)}?role=viewer${passwordQuery}`;

  setStatus("シグナリングサーバーに接続中...");

  let ws;
  try {
    ws = new WebSocket(wsUrl);
  } catch (err) {
    setStatus(`シグナリングURLが不正です: ${err.message}`);
    return;
  }

  teardownPeerConnection();
  const pc = new RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.cloudflare.com:3478" }],
  });
  currentPc = pc;

  pc.addEventListener("track", (event) => {
    videoEl.srcObject = event.streams[0];
    setStatus("配信中");
  });

  pc.addEventListener("icecandidate", (event) => {
    if (event.candidate) {
      ws.send(JSON.stringify({ type: "ice-candidate", candidate: event.candidate }));
    }
  });

  ws.addEventListener("open", () => {
    reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
    setStatus("配信開始を待っています...");
  });

  ws.addEventListener("message", async (event) => {
    const message = JSON.parse(event.data);

    if (message.type === "offer") {
      await pc.setRemoteDescription({ type: "offer", sdp: message.sdp });
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      ws.send(JSON.stringify({ type: "answer", sdp: answer.sdp }));
    } else if (message.type === "ice-candidate") {
      await pc.addIceCandidate(message.candidate);
    }
  });

  ws.addEventListener("close", () => {
    teardownPeerConnection();
    scheduleReconnect();
  });

  ws.addEventListener("error", () => {
    // close イベントが後続するため、再接続のスケジューリングは close 側のみで行う
    setStatus("接続エラーが発生しました");
  });
}

connect();
