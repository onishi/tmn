const params = new URLSearchParams(location.search);
const statusEl = document.getElementById("status");
const videoEl = document.getElementById("remote-video");
const fullscreenButton = document.getElementById("fullscreen-button");
const switchButton = document.getElementById("switch-button");
const sourceDialog = document.getElementById("source-dialog");
const currentRoomLabelEl = document.getElementById("current-room-label");
const sourceListEl = document.getElementById("source-list");
const addSourceForm = document.getElementById("add-source-form");
const addSourceLabelInput = document.getElementById("add-source-label");
const addSourceRoomInput = document.getElementById("add-source-room");
const closeDialogButton = document.getElementById("close-dialog-button");

const SOURCES_STORAGE_KEY = "tmn.sources";
const LAST_ROOM_STORAGE_KEY = "tmn.lastRoom";

const INITIAL_RECONNECT_DELAY_MS = 1000;
const MAX_RECONNECT_DELAY_MS = 15000;

let reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
let reconnectTimer = null;
let currentPc = null;
let currentWs = null;
// 配信元(シグナリングWebSocket)を切り替えるたびに増やす世代番号。
// 切り替え前の古い接続からの遅延イベントで状態を壊さないためのガード。
let connectionGeneration = 0;
let currentRoom = params.get("room") || localStorage.getItem(LAST_ROOM_STORAGE_KEY) || "";

function setStatus(text) {
  statusEl.textContent = text;
}

function loadSources() {
  try {
    const raw = localStorage.getItem(SOURCES_STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveSources(sources) {
  localStorage.setItem(SOURCES_STORAGE_KEY, JSON.stringify(sources));
}

function updateCurrentRoomLabel() {
  currentRoomLabelEl.textContent = currentRoom
    ? `現在の配信元トークン: ${currentRoom}`
    : "配信元が未設定です。下のフォームからルームトークンを追加してください。";
}

function renderSources() {
  const sources = loadSources();
  sourceListEl.innerHTML = "";

  if (sources.length === 0) {
    const empty = document.createElement("li");
    empty.className = "source-empty";
    empty.textContent = "保存された配信元はありません";
    sourceListEl.appendChild(empty);
    return;
  }

  for (const source of sources) {
    const li = document.createElement("li");
    li.className = "source-item";

    const selectButton = document.createElement("button");
    selectButton.type = "button";
    selectButton.className = "source-select";
    selectButton.textContent = source.room === currentRoom ? `● ${source.label}` : source.label;
    selectButton.addEventListener("click", () => {
      switchSource(source.room);
      sourceDialog.close();
    });
    li.appendChild(selectButton);

    const removeButton = document.createElement("button");
    removeButton.type = "button";
    removeButton.className = "source-remove";
    removeButton.textContent = "削除";
    removeButton.addEventListener("click", () => {
      saveSources(loadSources().filter((s) => s.room !== source.room));
      renderSources();
    });
    li.appendChild(removeButton);

    sourceListEl.appendChild(li);
  }
}

switchButton.addEventListener("click", () => {
  addSourceRoomInput.value = currentRoom;
  updateCurrentRoomLabel();
  renderSources();
  sourceDialog.showModal();
});

closeDialogButton.addEventListener("click", () => {
  sourceDialog.close();
});

addSourceForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const label = addSourceLabelInput.value.trim();
  const room = addSourceRoomInput.value.trim();
  if (!label || !room) return;

  const sources = loadSources().filter((s) => s.room !== room);
  sources.push({ room, label });
  saveSources(sources);
  addSourceLabelInput.value = "";
  renderSources();

  if (room !== currentRoom) {
    switchSource(room);
  }
});

function switchSource(newRoom) {
  const normalized = newRoom.trim();
  if (!normalized || normalized === currentRoom) return;

  currentRoom = normalized;
  localStorage.setItem(LAST_ROOM_STORAGE_KEY, currentRoom);

  const url = new URL(location.href);
  url.searchParams.set("room", currentRoom);
  history.replaceState(null, "", url);

  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  reconnectDelay = INITIAL_RECONNECT_DELAY_MS;

  if (currentWs) {
    try {
      currentWs.close();
    } catch {
      // ignore: 切り替え時点で既に切断済みの場合など
    }
  }

  connect();
}

// スマホでは映像を画面いっぱいに表示したいことが多いため、全画面ボタンを用意する。
// (Fullscreen APIはユーザー操作が起点でないと呼び出せないため、自動全画面化はしない)
if (document.fullscreenEnabled) {
  fullscreenButton.hidden = false;
  fullscreenButton.addEventListener("click", () => {
    if (document.fullscreenElement) {
      document.exitFullscreen();
    } else {
      videoEl.requestFullscreen().catch(() => {});
    }
  });
} else if (typeof videoEl.webkitEnterFullscreen === "function") {
  // iOS Safariは要素の汎用Fullscreen APIを持たず、<video>専用のネイティブ全画面のみ対応
  fullscreenButton.hidden = false;
  fullscreenButton.addEventListener("click", () => {
    videoEl.webkitEnterFullscreen();
  });
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
  if (!currentRoom) {
    setStatus("配信元が未設定です。右上の「配信元」から追加してください");
    return;
  }

  const { signalingUrl, accessPassword } = window.TMN_CONFIG;
  const passwordQuery = accessPassword ? `&password=${encodeURIComponent(accessPassword)}` : "";
  const wsUrl = `${signalingUrl}/room/${encodeURIComponent(currentRoom)}?role=viewer${passwordQuery}`;

  setStatus("シグナリングサーバーに接続中...");

  let ws;
  try {
    ws = new WebSocket(wsUrl);
  } catch (err) {
    setStatus(`シグナリングURLが不正です: ${err.message}`);
    return;
  }
  currentWs = ws;
  const myGeneration = ++connectionGeneration;

  teardownPeerConnection();
  const pc = new RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.cloudflare.com:3478" }],
  });
  currentPc = pc;

  pc.addEventListener("track", (event) => {
    if (myGeneration !== connectionGeneration) return;
    videoEl.srcObject = event.streams[0];
    setStatus("配信中");
  });

  pc.addEventListener("icecandidate", (event) => {
    if (myGeneration !== connectionGeneration) return;
    if (event.candidate) {
      ws.send(JSON.stringify({ type: "ice-candidate", candidate: event.candidate }));
    }
  });

  ws.addEventListener("open", () => {
    if (myGeneration !== connectionGeneration) return;
    reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
    setStatus("配信開始を待っています...");
  });

  ws.addEventListener("message", async (event) => {
    if (myGeneration !== connectionGeneration) return;
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
    if (myGeneration !== connectionGeneration) return;
    teardownPeerConnection();
    scheduleReconnect();
  });

  ws.addEventListener("error", () => {
    if (myGeneration !== connectionGeneration) return;
    // close イベントが後続するため、再接続のスケジューリングは close 側のみで行う
    setStatus("接続エラーが発生しました");
  });
}

connect();
