// 三个压测脚本共用的配置：阶梯加压、SLO 阈值、登录。
import http from 'k6/http';
import { check, fail } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

export const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080/api';
export const PASSWORD = __ENV.LOADTEST_PASSWORD || 'LoadTest-Passw0rd';
export const USERS = parseInt(__ENV.LOADTEST_USERS || '20', 10);

// 阶梯：每一级固定并发跑 STEP_SECONDS 秒，前一级结束后下一级才开始。
// 每一级是一个独立 scenario，k6 会自动给请求打上 scenario 标签，便于逐级统计拐点。
export const STEP_SECONDS = parseInt(__ENV.STEP_SECONDS || '30', 10);
export const WARMUP_SECONDS = parseInt(__ENV.WARMUP_SECONDS || '20', 10);
const DEFAULT_STEPS = [5, 10, 20, 40, 80, 120];

export function steps() {
  return __ENV.STEPS ? __ENV.STEPS.split(',').map((v) => parseInt(v, 10)) : DEFAULT_STEPS;
}

export function stepName(vus) {
  return `vu_${String(vus).padStart(3, '0')}`;
}

/**
 * 生成阶梯 scenario 和阈值。
 * slo：整个测试的 SLO（写进 thresholds，不达标时 k6 以非 0 退出码结束）。
 * 每一级还登记一个永远通过的阈值，只为让 k6 在 summary 里输出该级的子指标。
 */
export function buildOptions(endpoint, exec, slo) {
  // 预热：先低并发跑一段让 JIT、连接池、数据库缓存就绪，这段不计入任何阶梯。
  const scenarios = {
    warmup: { executor: 'constant-vus', exec, vus: 5, duration: `${WARMUP_SECONDS}s`, gracefulStop: '5s' },
  };
  const thresholds = {
    [`http_req_duration{ep:${endpoint}}`]: [`p(95)<${slo.p95Ms}`],
    [`http_req_failed{ep:${endpoint}}`]: [`rate<${slo.errorRate}`],
  };
  steps().forEach((vus, index) => {
    const name = stepName(vus);
    scenarios[name] = {
      executor: 'constant-vus',
      exec,
      vus,
      duration: `${STEP_SECONDS}s`,
      startTime: `${WARMUP_SECONDS + index * STEP_SECONDS}s`,
      gracefulStop: '5s',
    };
    thresholds[`http_req_duration{scenario:${name},ep:${endpoint}}`] = ['max>=0'];
    thresholds[`http_req_failed{scenario:${name},ep:${endpoint}}`] = ['rate>=0'];
    thresholds[`http_reqs{scenario:${name},ep:${endpoint}}`] = ['count>=0'];
  });
  return {
    scenarios,
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    // 登录等准备请求不计入任何阶梯。
    setupTimeout: '120s',
  };
}

export function account(index) {
  return `loadtest${String(index + 1).padStart(2, '0')}`;
}

export function login(userAccount, tags = { ep: 'setup' }) {
  const res = http.post(`${BASE}/user/login`, JSON.stringify({ userAccount, userPassword: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
    tags,
  });
  return res;
}

/** setup 阶段为每个压测账号登录一次，VU 按编号轮流使用这些 token。 */
export function loginAll() {
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    const res = login(account(i));
    if (res.status !== 200) {
      fail(`login failed for ${account(i)}: ${res.status} ${res.body}`);
    }
    tokens.push(res.json('data.accessToken'));
  }
  return { tokens };
}

export function authHeaders(data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  return { Authorization: `Bearer ${token}` };
}

/** 业务成功：HTTP 2xx 且 BaseResponse.code == 0。 */
export function checkOk(res, name) {
  const ok = check(res, {
    [`${name} status 2xx`]: (r) => r.status >= 200 && r.status < 300,
    [`${name} code 0`]: (r) => {
      try {
        return r.json('code') === 0;
      } catch (e) {
        return false;
      }
    },
  });
  // 抽样打印失败原因（status=0 表示连接层错误，请求没有到达应用）。
  if (!ok && Math.random() < 0.2) {
    console.warn(`${name} failed: status=${res.status} error=${res.error} body=${String(res.body).slice(0, 120)}`);
  }
  return ok;
}

/** 把 summary 同时写成 JSON 文件（供 report.py 生成拐点表）和终端摘要。 */
export function writeSummary(data, endpoint) {
  const label = __ENV.RUN_LABEL || 'run';
  // report.py 用每级时长把请求数换算成 QPS。
  data.stepSeconds = STEP_SECONDS;
  return {
    [`/results/${endpoint}-${label}.json`]: JSON.stringify(data, null, 2),
    // 自定义 handleSummary 会替换 k6 默认的终端汇总，这里把标准汇总（含 THRESHOLDS ✓/✗）补回来。
    stdout: `${textSummary(data, { indent: ' ', enableColors: true })}\nsummary written to loadtest/results/${endpoint}-${label}.json\n`,
  };
}
