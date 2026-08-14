// デプロイ先のシグナリングWorkerのURLに書き換えること
window.TMN_CONFIG = {
  signalingUrl: "wss://tmn-signaling.<your-subdomain>.workers.dev",
  // シグナリングWorker側で ACCESS_PASSWORD を設定している場合のみ、同じ値を設定する(任意)
  accessPassword: "",
};
