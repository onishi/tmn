// 簡易パスワード認証(ACCESS_PASSWORD)の動作を手動検証するスクリプト。
// 事前に .dev.vars に ACCESS_PASSWORD を設定した状態で `npm run dev` を起動しておくこと。
// 実行: TEST_ACCESS_PASSWORD=<.dev.varsに設定した値> node test/manual-auth-check.mjs

const ROOM = "test-room-token-1234";
const password = process.env.TEST_ACCESS_PASSWORD;

if (!password) {
  console.error(
    "TEST_ACCESS_PASSWORD env var is required. .dev.vars の ACCESS_PASSWORD と同じ値を渡すこと。"
  );
  process.exit(1);
}

function attempt(query) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://localhost:8787/room/${ROOM}?role=viewer${query}`);
    const timer = setTimeout(() => {
      ws.close();
      resolve("timeout");
    }, 3000);

    ws.addEventListener("open", () => {
      clearTimeout(timer);
      resolve("open");
      ws.close();
    });
    ws.addEventListener("close", () => {
      clearTimeout(timer);
      resolve("closed-without-open");
    });
    ws.addEventListener("error", () => {
      // close イベントが後続するため、ここでは何もしない
    });
  });
}

async function main() {
  const noPasswordResult = await attempt("");
  console.log("no password:", noPasswordResult);
  if (noPasswordResult === "open") {
    throw new Error("FAILED: connected without a password");
  }

  const wrongPasswordResult = await attempt("&password=wrong-password-xyz");
  console.log("wrong password:", wrongPasswordResult);
  if (wrongPasswordResult === "open") {
    throw new Error("FAILED: connected with a wrong password");
  }

  const correctPasswordResult = await attempt(`&password=${encodeURIComponent(password)}`);
  console.log("correct password:", correctPasswordResult);
  if (correctPasswordResult !== "open") {
    throw new Error("FAILED: could not connect with the correct password");
  }

  console.log("\nALL AUTH CHECKS PASSED");
  process.exit(0);
}

main().catch((err) => {
  console.error("FAILED:", err);
  process.exit(1);
});
