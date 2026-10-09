// 压测 POST /user/login（自选接口）：BCrypt 校验是 CPU 密集型操作，用来观察 CPU 瓶颈下的拐点。
import { account, buildOptions, checkOk, login, USERS, writeSummary } from './lib.js';

const ENDPOINT = 'login';

// SLO：p95 < 300ms（BCrypt 单次本身约几十毫秒），错误率 < 1%。
export const options = buildOptions(ENDPOINT, 'loginOnce', { p95Ms: 300, errorRate: 0.01 });

export function loginOnce() {
  const res = login(account((__VU - 1) % USERS), { ep: ENDPOINT });
  checkOk(res, ENDPOINT);
}

export function handleSummary(data) {
  return writeSummary(data, ENDPOINT);
}
