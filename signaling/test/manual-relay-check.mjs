// シグナリングWorkerの中継ロジックを手動検証するスクリプト。
// 事前に `npm run dev` でローカルサーバー(ws://localhost:8787)を起動しておくこと。
// 実行: node test/manual-relay-check.mjs

const ROOM = "test-room-token-1234";
const base = `ws://localhost:8787/room/${ROOM}`;
// サーバー側で ACCESS_PASSWORD (.dev.vars) を設定している場合は、
// 同じ値を TEST_ACCESS_PASSWORD 環境変数で渡す。
const password = process.env.TEST_ACCESS_PASSWORD;
const passwordQuery = password ? `&password=${encodeURIComponent(password)}` : "";

function connect(role) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base}?role=${role}${passwordQuery}`);
    const received = [];
    ws.addEventListener("message", (e) => received.push(JSON.parse(e.data)));
    ws.addEventListener("open", () => resolve({ ws, received }));
    ws.addEventListener("error", (e) => reject(e));
  });
}

function waitFor(received, predicate, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const iv = setInterval(() => {
      const found = received.find(predicate);
      if (found) {
        clearInterval(iv);
        resolve(found);
      } else if (Date.now() - start > timeoutMs) {
        clearInterval(iv);
        reject(new Error("timeout waiting for message: " + JSON.stringify(received)));
      }
    }, 50);
  });
}

async function main() {
  const caster = await connect("caster");
  console.log("caster connected");

  const viewer = await connect("viewer");
  console.log("viewer connected");

  // 1. viewer接続をトリガーにcasterへ通知が飛ぶこと
  const joined = await waitFor(caster.received, (m) => m.type === "viewer-joined");
  console.log("OK: caster received", joined);

  // 2. caster -> viewer への offer 中継
  caster.ws.send(JSON.stringify({ type: "offer", sdp: "fake-sdp-offer" }));
  const offer = await waitFor(viewer.received, (m) => m.type === "offer");
  console.log("OK: viewer received offer", offer);

  // 3. viewer -> caster への answer 中継
  viewer.ws.send(JSON.stringify({ type: "answer", sdp: "fake-sdp-answer" }));
  const answer = await waitFor(caster.received, (m) => m.type === "answer");
  console.log("OK: caster received answer", answer);

  // 4. ICE candidate 双方向中継
  caster.ws.send(JSON.stringify({ type: "ice-candidate", candidate: "cand-from-caster" }));
  await waitFor(viewer.received, (m) => m.type === "ice-candidate" && m.candidate === "cand-from-caster");
  console.log("OK: viewer received ice-candidate from caster");

  viewer.ws.send(JSON.stringify({ type: "ice-candidate", candidate: "cand-from-viewer" }));
  await waitFor(caster.received, (m) => m.type === "ice-candidate" && m.candidate === "cand-from-viewer");
  console.log("OK: caster received ice-candidate from viewer");

  // 5. viewer切断でcasterにviewer-leftが通知される
  viewer.ws.close();
  await waitFor(caster.received, (m) => m.type === "viewer-left");
  console.log("OK: caster received viewer-left");

  caster.ws.close();
  console.log("\nALL CHECKS PASSED");
  process.exit(0);
}

main().catch((err) => {
  console.error("FAILED:", err);
  process.exit(1);
});
