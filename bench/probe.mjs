// 基准测试探针：连接 PC 服务端 WebSocket，触发屏幕推流并收集 screen_stats / screen_state。
// 用法：node probe.mjs [wsUrl] [monitor] [seconds] [outFile]
import fs from 'node:fs';

const [, , url = 'ws://127.0.0.1:8500', monitorArg = '0', secondsArg = '30', out = 'probe.json'] = process.argv;
const monitor = Number(monitorArg);
const seconds = Number(secondsArg);
const stats = [];
const states = [];

const ws = new WebSocket(url);

function finish(extra) {
  const result = {
    url,
    monitor,
    seconds,
    ...extra,
    stats,
    states,
  };
  fs.writeFileSync(out, JSON.stringify(result, null, 2));
  process.exit(0);
}

const timer = setTimeout(() => {
  try {
    ws.send(JSON.stringify({ type: 'screen_control', action: 'stop' }));
  } catch {
    // 忽略：连接可能已断开
  }
  setTimeout(() => {
    try {
      ws.close();
    } catch {
      // 忽略
    }
    finish({});
  }, 500);
}, seconds * 1000);

ws.addEventListener('open', () => {
  ws.send(JSON.stringify({ type: 'screen_control', action: 'start', monitor }));
});

ws.addEventListener('message', (event) => {
  if (typeof event.data !== 'string') {
    return;
  }
  try {
    const message = JSON.parse(event.data);
    if (message.type === 'screen_stats') {
      stats.push({ fps: message.fps, actualFps: message.actualFps, width: message.width, height: message.height, kbps: message.kbps });
    } else if (message.type === 'screen_state') {
      states.push({ streaming: message.streaming, fps: message.fps, width: message.width, height: message.height, reason: message.reason ?? null });
    }
  } catch {
    // 忽略非 JSON 文本
  }
});

ws.addEventListener('error', (event) => {
  clearTimeout(timer);
  finish({ error: String(event.message ?? 'websocket error') });
});
