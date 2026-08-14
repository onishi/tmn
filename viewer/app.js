const params = new URLSearchParams(location.search);
const room = params.get("room");
const statusEl = document.getElementById("status");
const videoEl = document.getElementById("remote-video");

function setStatus(text) {
  statusEl.textContent = text;
}

async function main() {
  if (!room) {
    setStatus("URLに ?room=<トークン> が必要です");
    return;
  }

  const { signalingUrl } = window.TMN_CONFIG;
  const wsUrl = `${signalingUrl}/room/${encodeURIComponent(room)}?role=viewer`;

  setStatus("シグナリングサーバーに接続中...");
  const ws = new WebSocket(wsUrl);

  const pc = new RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.cloudflare.com:3478" }],
  });

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
    setStatus("接続が切断されました");
  });

  ws.addEventListener("error", () => {
    setStatus("接続エラー");
  });
}

main();
