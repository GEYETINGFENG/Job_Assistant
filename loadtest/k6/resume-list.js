// 压测 GET /resumes：每个压测账号约 4750 份有效简历，请求第一页 20 条。
import http from 'k6/http';
import { BASE, authHeaders, buildOptions, checkOk, loginAll, writeSummary } from './lib.js';

const ENDPOINT = 'list';

// SLO：p95 < 200ms，错误率 < 1%。
export const options = buildOptions(ENDPOINT, 'listResumes', { p95Ms: 200, errorRate: 0.01 });

export function setup() {
  return loginAll();
}

export function listResumes(data) {
  const res = http.get(`${BASE}/resumes?page=0&size=20`, { headers: authHeaders(data), tags: { ep: ENDPOINT } });
  checkOk(res, ENDPOINT);
}

export function handleSummary(data) {
  return writeSummary(data, ENDPOINT);
}
