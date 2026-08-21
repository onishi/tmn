const params = new URLSearchParams(location.search);
const videoEl = document.getElementById("remote-video");

const statusBarEl = document.getElementById("status-bar");
const statusDotEl = document.getElementById("status-dot");
const cameraNameEl = document.getElementById("camera-name");
const statusDetailEl = document.getElementById("status-detail");
const detectionBadgeEl = document.getElementById("detection-badge");
const viewerCountBadgeEl = document.getElementById("viewer-count-badge");

const centerMessageEl = document.getElementById("center-message");
const centerIconEl = document.getElementById("center-icon");
const centerTextEl = document.getElementById("center-text");
const centerActionEl = document.getElementById("center-action");

const fullscreenButton = document.getElementById("fullscreen-button");
const connectionButton = document.getElementById("connection-button");
const switchButton = document.getElementById("switch-button");
const sourceDialog = document.getElementById("source-dialog");
const sourceListEl = document.getElementById("source-list");
const addSourceToggle = document.getElementById("add-source-toggle");
const addSourceForm = document.getElementById("add-source-form");
const addSourceLabelInput = document.getElementById("add-source-label");
const addSourceRoomInput = document.getElementById("add-source-room");
const addSourceStatusEl = document.getElementById("add-source-status");
const closeDialogButton = document.getElementById("close-dialog-button");

const SOURCES_STORAGE_KEY = "tmn.sources";
const LAST_ROOM_STORAGE_KEY = "tmn.lastRoom";

const INITIAL_RECONNECT_DELAY_MS = 1000;
const MAX_RECONNECT_DELAY_MS = 15000;

// マウス・キー操作等がこの時間ない場合、見ている人がいないとみなして自動切断する
// (放置されたタブが気づかれないまま配信され続けるのを防ぐため)
const IDLE_DISCONNECT_MS = 10 * 60 * 1000;

// タブが非表示になってからこの時間経っても戻ってこなければ切断する
// (通知確認などですぐ戻ってくる場合まで切断しないよう、猶予を持たせる)
const BACKGROUND_DISCONNECT_DELAY_MS = 30 * 1000;

let reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
let reconnectTimer = null;
let idleTimer = null;
let backgroundTimer = null;
let currentPc = null;
let currentWs = null;
// Roomから割り当てられる、自分自身を識別するID。answer/ice-candidateに添えて送り返すことで、
// Caster側が複数視聴者それぞれに対応するPeerConnectionへ正しく振り分けられるようにする。
let myViewerId = null;
// 配信元(シグナリングWebSocket)を切り替えるたびに増やす世代番号。
// 切り替え前の古い接続からの遅延イベントで状態を壊さないためのガード。
let connectionGeneration = 0;
let currentRoom = params.get("room") || localStorage.getItem(LAST_ROOM_STORAGE_KEY) || "";

// --- アイコン ---

const ICONS = {
  spinner:
    '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 2a10 10 0 0 1 10 10" opacity="0.9"/></svg>',
  camera:
    '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="6" width="14" height="12" rx="2"/><path d="M16 10l6-4v12l-6-4"/></svg>',
  alert:
    '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 9v4"/><path d="M12 17h.01"/><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/></svg>',
  expand:
    '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/></svg>',
  collapse:
    '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3v3a2 2 0 0 1-2 2H3"/><path d="M21 8h-3a2 2 0 0 1-2-2V3"/><path d="M3 16h3a2 2 0 0 1 2 2v3"/><path d="M16 21v-3a2 2 0 0 1 2-2h3"/></svg>',
  trash:
    '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>',
  // Material Symbols (https://fonts.google.com/icons) の airplay / mimo_disconnect を使用
  connect:
    '<svg xmlns="http://www.w3.org/2000/svg" height="32" viewBox="0 -960 960 960" width="32" fill="currentColor"><path d="m240-120 240-240 240 240H240ZM80-280v-480q0-33 23.5-56.5T160-840h640q33 0 56.5 23.5T880-760v480q0 33-23.5 56.5T800-200H680v-80h120v-480H160v480h120v80H160q-33 0-56.5-23.5T80-280Zm400-200Z"/></svg>',
  disconnect:
    '<svg xmlns="http://www.w3.org/2000/svg" height="32" viewBox="0 -960 960 960" width="32" fill="currentColor"><path d="m127-833 73 73h-40v440h368L28-820l56-56L876-84l-56 56-212-212h72l40 40v80H240v-80l40-40H160q-33 0-56.5-23.5T80-320v-440q0-37 23.5-55l23.5-18Zm673-7q33 0 56.5 23.5T880-760v440q0 26-14.5 46T828-246l-28-28v-486H314l-80-80h566ZM557-517Zm-213 13Z"/></svg>',
};

// --- 動物・人の検知状態(視聴を開始する前でも確認できる) ---
//
// Casterは検知結果をシグナリングWorkerへ送っており、Room(Durable Object)が直近の
// 1件をキャッシュしている。視聴用のWebSocketを開かなくても GET /room/<token>/status
// で確認できるため、配信元一覧の各カメラや現在の配信元について「視聴を始める前に」
// 検知状況を確認できる。

function detectionStatusUrl(room) {
  const { signalingUrl, accessPassword } = window.TMN_CONFIG;
  const httpBase = signalingUrl.replace(/^wss:\/\//, "https://").replace(/^ws:\/\//, "http://");
  const passwordQuery = accessPassword ? `?password=${encodeURIComponent(accessPassword)}` : "";
  return `${httpBase}/room/${encodeURIComponent(room)}/status${passwordQuery}`;
}

async function fetchDetectionStatus(room) {
  try {
    const res = await fetch(detectionStatusUrl(room));
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null; // オフライン・未デプロイ環境などでは静かに諦める(視聴自体は妨げない)
  }
}

/**
 * 視聴(動画のWebSocket接続)を開始していなくても、動物・人検知の状況と現在の
 * 視聴者数だけは定期的に確認してバッジへ反映する。Casterは視聴者がいない待機中でも
 * 一定間隔で見回り検知しているため、それを見るためにわざわざ「接続する」を
 * 押させる必要はない。視聴者数も同じ/status エンドポイントから取得できる。
 */
function refreshStatus() {
  if (!currentRoom) return;
  const roomAtFetchTime = currentRoom;
  fetchDetectionStatus(roomAtFetchTime).then((status) => {
    if (currentRoom !== roomAtFetchTime) return;
    setDetectionBadge(status);
    // 接続中は"viewer-count"のWebSocketメッセージで既にリアルタイム更新されているため、
    // ここでの上書きは未接続時(currentWsがない)にのみ意味を持つが、
    // 上書きしても実質的に同じ値になるだけなので接続状態を問わず反映してよい
    setViewerCountBadge(typeof status?.viewerCount === "number" ? status.viewerCount : null);
  });
}

const STATUS_POLL_INTERVAL_MS = 2 * 60 * 1000;
setInterval(refreshStatus, STATUS_POLL_INTERVAL_MS);

// --- TURN(中継サーバー)認証情報 ---
//
// 同じWi-Fiルーター配下同士でも、ルーターがNATヘアピンに対応していないとSTUNだけでは
// P2P接続が確立できないことがある。シグナリングWorker経由でCloudflare CallsのTURN認証情報を
// 取得し、STUNに加えてフォールバックとして使う。Worker側でTURNが未設定の環境では
// iceServers: null が返るため、その場合はSTUNのみで従来通り動作する。

function turnCredentialsUrl() {
  const { signalingUrl, accessPassword } = window.TMN_CONFIG;
  const httpBase = signalingUrl.replace(/^wss:\/\//, "https://").replace(/^ws:\/\//, "http://");
  const passwordQuery = accessPassword ? `?password=${encodeURIComponent(accessPassword)}` : "";
  return `${httpBase}/turn-credentials${passwordQuery}`;
}

async function fetchTurnIceServers() {
  try {
    const res = await fetch(turnCredentialsUrl());
    if (!res.ok) return [];
    const data = await res.json();
    return data.iceServers ? [data.iceServers] : [];
  } catch {
    return []; // TURNが取得できなくても、STUNのみで接続を試みる
  }
}

function detectionBadgeText(status) {
  if (!status || (!status.hasAnimal && !status.hasPerson)) return "";
  if (status.hasAnimal && status.hasPerson) return "🐾🧍";
  if (status.hasAnimal) return "🐾";
  return "🧍";
}

/** 現在の配信元(ステータスバー)の検知バッジを更新する */
function setDetectionBadge(status) {
  detectionBadgeEl.textContent = detectionBadgeText(status);
}

/** 同時視聴者数のバッジを更新する(接続中のみ表示。切断中はnullを渡して非表示にする) */
function setViewerCountBadge(count) {
  viewerCountBadgeEl.textContent = typeof count === "number" ? `👀${count}` : "";
}

// --- 配信元(ルームトークン)の保存・切り替え ---

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

function findLabelForRoom(room) {
  const source = loadSources().find((s) => s.room === room);
  return source ? source.label : "";
}

function renderSources() {
  const sources = loadSources();
  sourceListEl.innerHTML = "";

  if (sources.length === 0) {
    const empty = document.createElement("li");
    empty.className = "source-empty";
    empty.textContent = "まだカメラが登録されていません。下のボタンから追加してください。";
    sourceListEl.appendChild(empty);
    return;
  }

  for (const source of sources) {
    const isActive = source.room === currentRoom;

    const li = document.createElement("li");
    li.className = "source-item" + (isActive ? " is-active" : "");

    const selectButton = document.createElement("button");
    selectButton.type = "button";
    selectButton.className = "source-select";
    selectButton.textContent = source.label;
    selectButton.addEventListener("click", () => {
      switchSource(source.room);
      sourceDialog.close();
    });
    li.appendChild(selectButton);

    const badge = document.createElement("span");
    badge.className = "source-badge";
    li.appendChild(badge);
    // 視聴を始める前に(=WebSocketを開かずに)、このカメラの直近の検知結果を確認する
    fetchDetectionStatus(source.room).then((status) => {
      badge.textContent = detectionBadgeText(status);
    });

    const dot = document.createElement("span");
    dot.className = "source-dot";
    li.appendChild(dot);

    const removeButton = document.createElement("button");
    removeButton.type = "button";
    removeButton.className = "source-remove";
    removeButton.innerHTML = ICONS.trash;
    removeButton.setAttribute("aria-label", `${source.label}を削除`);
    removeButton.addEventListener("click", () => {
      if (!confirm(`「${source.label}」を一覧から削除しますか?`)) return;
      saveSources(loadSources().filter((s) => s.room !== source.room));
      renderSources();
    });
    li.appendChild(removeButton);

    sourceListEl.appendChild(li);
  }
}

function setAddFormVisible(visible) {
  addSourceForm.hidden = !visible;
  addSourceToggle.hidden = visible;
  if (visible) {
    addSourceStatusEl.textContent = "";
    addSourceLabelInput.focus();
  }
}

addSourceToggle.addEventListener("click", () => setAddFormVisible(true));

switchButton.addEventListener("click", () => {
  renderSources();
  const sources = loadSources();
  const currentIsSaved = sources.some((s) => s.room === currentRoom);
  setAddFormVisible(sources.length === 0);
  addSourceLabelInput.value = "";
  // 現在見ている配信元がまだ未保存なら、トークン欄に入力の手間を省いておく
  addSourceRoomInput.value = currentRoom && !currentIsSaved ? currentRoom : "";
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

  const hadNoRoom = !currentRoom;
  const isCurrentRoom = room === currentRoom;

  const sources = loadSources().filter((s) => s.room !== room);
  sources.push({ room, label });
  saveSources(sources);

  addSourceLabelInput.value = "";
  addSourceRoomInput.value = "";
  renderSources();
  setAddFormVisible(false);

  if (hadNoRoom) {
    // 何も見ていない状態からの追加は、そのまま視聴開始するのが自然
    switchSource(room);
    sourceDialog.close();
    return;
  }

  if (isCurrentRoom) {
    addSourceStatusEl.textContent = `「${label}」として保存しました。`;
  } else {
    // 既に別のカメラを見ている場合は、追加しても視聴先は変えない
    // (タップで見ている映像が急に切り替わると驚かせてしまうため)
    addSourceStatusEl.textContent = `「${label}」を保存しました。タップすると切り替わります。`;
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

// --- 全画面表示 ---
// (Fullscreen APIはユーザー操作が起点でないと呼び出せないため、自動全画面化はしない)

function updateFullscreenIcon() {
  fullscreenButton.innerHTML = document.fullscreenElement ? ICONS.collapse : ICONS.expand;
}

if (document.fullscreenEnabled) {
  fullscreenButton.hidden = false;
  updateFullscreenIcon();
  fullscreenButton.addEventListener("click", () => {
    if (document.fullscreenElement) {
      document.exitFullscreen();
    } else {
      videoEl.requestFullscreen().catch(() => {});
    }
  });
  document.addEventListener("fullscreenchange", updateFullscreenIcon);
} else if (typeof videoEl.webkitEnterFullscreen === "function") {
  // iOS Safariは要素の汎用Fullscreen APIを持たず、<video>専用のネイティブ全画面のみ対応
  fullscreenButton.hidden = false;
  fullscreenButton.innerHTML = ICONS.expand;
  fullscreenButton.addEventListener("click", () => {
    videoEl.webkitEnterFullscreen();
  });
}

// --- 状態表示 ---

function setState(state, { detail = "", centerText = "", centerIcon = "", spin = false, action = null } = {}) {
  statusBarEl.dataset.state = state;
  cameraNameEl.textContent = currentRoom ? findLabelForRoom(currentRoom) || "見守りカメラ" : "TMN 見守りカメラ";
  statusDetailEl.textContent = detail;

  // 画面下部の接続・切断ボタン。カメラ未設定(idle)では出しようがないので隠す
  if (state === "idle") {
    connectionButton.hidden = true;
  } else if (state === "disconnected") {
    connectionButton.hidden = false;
    connectionButton.className = "is-connect";
    connectionButton.innerHTML = ICONS.connect;
    connectionButton.setAttribute("aria-label", "接続する");
    connectionButton.title = "接続する";
    connectionButton.onclick = () => connect();
  } else {
    connectionButton.hidden = false;
    connectionButton.className = "is-disconnect";
    connectionButton.innerHTML = ICONS.disconnect;
    connectionButton.setAttribute("aria-label", "切断");
    connectionButton.title = "切断";
    connectionButton.onclick = () => disconnect();
  }

  if (state === "streaming") {
    centerMessageEl.hidden = true;
    return;
  }

  centerMessageEl.hidden = false;
  centerIconEl.innerHTML = centerIcon || ICONS.camera;
  centerIconEl.className = spin ? "spin" : "";
  centerTextEl.textContent = centerText;

  if (action) {
    centerActionEl.hidden = false;
    centerActionEl.textContent = action.label;
    centerActionEl.onclick = action.onClick;
  } else {
    centerActionEl.hidden = true;
    centerActionEl.onclick = null;
  }
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  const seconds = Math.round(reconnectDelay / 1000);
  setState("reconnecting", {
    detail: `${seconds}秒後に再接続...`,
    centerIcon: ICONS.alert,
    centerText: `接続が切れました。${seconds}秒後に自動で再接続します。`,
  });
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

/**
 * 接続を終了し、「接続する」ボタンを待つ状態に戻す共通処理。
 * ユーザーの明示的な切断・Casterからの配信停止検知のどちらからも呼ばれる。
 * Caster側は視聴者ごとのPeerConnectionを閉じた後、その視聴者へ改めてofferを
 * 送り直す手段を持たないため、配信が止まった場合もWebSocketごと閉じて
 * 「接続する」を押し直せば最初からやり直せるようにする。
 */
function disconnectInternal(centerText) {
  // 世代を進めることで、進行中のconnect()や保留中の再接続タイマー・イベントを
  // 無効化する(ws"close"イベントのハンドラもこのガードでscheduleReconnectをスキップする)
  connectionGeneration++;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  reconnectDelay = INITIAL_RECONNECT_DELAY_MS;

  if (currentWs) {
    try {
      currentWs.close();
    } catch {
      // ignore: 切断時点で既に切れている場合など
    }
    currentWs = null;
  }
  teardownPeerConnection();
  myViewerId = null;
  clearIdleTimer();
  clearBackgroundTimer();

  setState("disconnected", { centerText });
  refreshStatus();
}

/** ユーザー操作による明示的な切断。自動再接続はせず、「接続する」ボタンが押されるまで待つ */
function disconnect() {
  disconnectInternal("接続すると視聴を開始します。");
}

/**
 * 配信中だった接続が、ユーザー操作でも通常のWebSocket切断でもなく途切れた場合の処理。
 * Caster側が配信モードをOFFにした・視聴者がタイムアウトで切られた等が該当する。
 * 最後のフレームが残ったまま固まって見えるのを避け、はっきり「止まった」と分かる表示にする
 */
function handleStreamStopped() {
  disconnectInternal("Casterが配信を停止しました。再度接続すると視聴を再開できます。");
}

function clearIdleTimer() {
  if (idleTimer) {
    clearTimeout(idleTimer);
    idleTimer = null;
  }
}

function clearBackgroundTimer() {
  if (backgroundTimer) {
    clearTimeout(backgroundTimer);
    backgroundTimer = null;
  }
}

/** 接続中に操作があるたびに呼ぶ。無操作タイマーを最初から数え直す */
function noteActivity() {
  if (!currentWs) return; // 未接続時はタイマー自体不要
  clearIdleTimer();
  idleTimer = setTimeout(() => {
    idleTimer = null;
    disconnectInternal("しばらく操作がなかったため、自動的に接続を解除しました。");
  }, IDLE_DISCONNECT_MS);
}

// マウス・タッチ・キー操作・スクロールを広く拾い、接続中は無操作タイマーを延長し続ける。
// 動画を眺めているだけでも定期的にどれかは発生する想定(全く触れず放置された場合のみ切断する)
["mousemove", "mousedown", "keydown", "touchstart", "scroll", "wheel"].forEach((eventName) => {
  window.addEventListener(eventName, noteActivity, { passive: true });
});

async function connect() {
  if (!currentRoom) {
    setDetectionBadge(null);
    setState("idle", {
      centerText: "見守りカメラが設定されていません。カメラを追加すると視聴を開始できます。",
      action: {
        label: "カメラを追加",
        onClick: () => switchButton.click(),
      },
    });
    return;
  }

  // 動画のWebSocket接続とは独立に、直近の検知結果だけ先に確認しておく
  // (接続に多少時間がかかっても、動物・人がいたかどうかはすぐ分かる)
  setDetectionBadge(null);
  const roomAtFetchTime = currentRoom;
  fetchDetectionStatus(roomAtFetchTime).then((status) => {
    if (currentRoom === roomAtFetchTime) setDetectionBadge(status);
  });

  const myGeneration = ++connectionGeneration;

  setState("connecting", {
    detail: "接続中...",
    spin: true,
    centerIcon: ICONS.spinner,
    centerText: "カメラに接続しています…",
  });

  const turnIceServers = await fetchTurnIceServers();
  // 取得を待っている間に切り替え・別の接続要求が来ていたら、こちらは何もせず諦める
  if (myGeneration !== connectionGeneration) return;

  const { signalingUrl, accessPassword } = window.TMN_CONFIG;
  const passwordQuery = accessPassword ? `&password=${encodeURIComponent(accessPassword)}` : "";
  const wsUrl = `${signalingUrl}/room/${encodeURIComponent(currentRoom)}?role=viewer${passwordQuery}`;

  let ws;
  try {
    ws = new WebSocket(wsUrl);
  } catch (err) {
    setState("error", {
      detail: "接続できません",
      centerIcon: ICONS.alert,
      centerText: `シグナリングURLが正しくない可能性があります: ${err.message}`,
    });
    return;
  }
  currentWs = ws;
  noteActivity(); // 無操作タイマーを開始する

  teardownPeerConnection();
  const pc = new RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.cloudflare.com:3478" }, ...turnIceServers],
  });
  currentPc = pc;

  // 一度でも配信中(streaming)に到達したかどうか。到達前のconnectionState変化は
  // 初回接続確立中の過程でしかないため、handleStreamStopped()の対象外にする
  let hasStreamed = false;

  pc.addEventListener("track", (event) => {
    if (myGeneration !== connectionGeneration) return;
    hasStreamed = true;
    videoEl.srcObject = event.streams[0];
    setState("streaming");

    const [remoteTrack] = event.streams[0].getVideoTracks();
    remoteTrack?.addEventListener("ended", () => {
      if (myGeneration !== connectionGeneration) return;
      handleStreamStopped();
    });
  });

  pc.addEventListener("icecandidate", (event) => {
    if (myGeneration !== connectionGeneration) return;
    if (event.candidate) {
      ws.send(JSON.stringify({ type: "ice-candidate", candidate: event.candidate, viewerId: myViewerId }));
    }
  });

  pc.addEventListener("connectionstatechange", () => {
    if (myGeneration !== connectionGeneration) return;
    if (!hasStreamed) return; // 初回接続確立中の一時的な状態変化は無視する
    if (["disconnected", "failed", "closed"].includes(pc.connectionState)) {
      handleStreamStopped();
    }
  });

  ws.addEventListener("open", () => {
    if (myGeneration !== connectionGeneration) return;
    reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
    setState("waiting", {
      detail: "配信開始を待っています",
      spin: true,
      centerIcon: ICONS.spinner,
      centerText: "配信アプリからの応答を待っています…",
    });
  });

  ws.addEventListener("message", async (event) => {
    if (myGeneration !== connectionGeneration) return;
    const message = JSON.parse(event.data);

    if (message.type === "welcome") {
      myViewerId = message.viewerId;
    } else if (message.type === "offer") {
      await pc.setRemoteDescription({ type: "offer", sdp: message.sdp });
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      ws.send(JSON.stringify({ type: "answer", sdp: answer.sdp, viewerId: myViewerId }));
    } else if (message.type === "ice-candidate") {
      await pc.addIceCandidate(message.candidate);
    } else if (message.type === "detection-status") {
      // 視聴中にCasterから新しい検知結果が届いた場合、リアルタイムでバッジを更新する
      setDetectionBadge({ hasAnimal: message.hasAnimal, hasPerson: message.hasPerson });
    } else if (message.type === "viewer-count") {
      setViewerCountBadge(message.count);
    } else if (message.type === "ping") {
      // Worker側の生存確認。応答がないと接続が死んでいるとみなされ、
      // 視聴者数のカウントから外される
      ws.send(JSON.stringify({ type: "pong" }));
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
    setState("error", { detail: "接続エラー" });
  });
}

// タブが非表示(他タブに切り替え・画面ロック・アプリのバックグラウンド化)になったら、
// 一定時間後に接続中の視聴を自動的に切断する。見ていない間もCaster側のカメラ・帯域を
// 無駄に使い続けないようにするため。通知確認などですぐタブに戻ってきた場合は
// 切断しないよう、非表示になった瞬間ではなく猶予を置いてから切断する。
// 再表示時の自動再接続はせず、「接続する」ボタンを待つ
document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    if (currentWs && !backgroundTimer) {
      backgroundTimer = setTimeout(() => {
        backgroundTimer = null;
        disconnectInternal("バックグラウンドで一定時間経過したため、自動的に接続を解除しました。");
      }, BACKGROUND_DISCONNECT_DELAY_MS);
    }
  } else {
    clearBackgroundTimer();
  }
});

// デフォルトでは自動接続しない。ルームが設定されていれば「接続する」ボタンを、
// 未設定なら従来通り「カメラを追加」の案内を表示する
if (!currentRoom) {
  setState("idle", {
    centerText: "見守りカメラが設定されていません。カメラを追加すると視聴を開始できます。",
    action: {
      label: "カメラを追加",
      onClick: () => switchButton.click(),
    },
  });
} else {
  setState("disconnected", {
    centerText: "接続すると視聴を開始します。",
  });
  refreshStatus();
}
