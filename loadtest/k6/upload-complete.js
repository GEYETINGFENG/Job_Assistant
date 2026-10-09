// 压测 POST /resumes/uploads/{id}/complete。
// 每次迭代：申请预签名 → PUT 到 S3 → 调用 complete。只有 complete 打上 ep=complete 标签参与统计。
// 注意：会真实读写 S3 并让 worker 处理任务；压测前请把 RESUME_AI_ENABLED 设为 false。
import http from 'k6/http';
import { check } from 'k6';
import { BASE, authHeaders, buildOptions, checkOk, loginAll, writeSummary } from './lib.js';

const ENDPOINT = 'complete';

// complete 同步执行 S3 HEAD + 条件复制，延迟受应用到 S3 所在区域的网络影响，SLO 放宽到 p95 < 800ms。
export const options = buildOptions(ENDPOINT, 'uploadAndComplete', { p95Ms: 800, errorRate: 0.01 });

// 最小合法 PDF（Tika 能解析出一行文字）。
const PDF = [
  '%PDF-1.4',
  '1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj',
  '2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj',
  '3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj',
  '4 0 obj << /Length 44 >> stream',
  'BT /F1 12 Tf 72 720 Td (Load Test Resume) Tj ET',
  'endstream endobj',
  '5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj',
  'trailer << /Root 1 0 R >>',
  '%%EOF',
].join('\n');

export function setup() {
  return loginAll();
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function uploadAndComplete(data) {
  const auth = authHeaders(data);
  const presign = http.post(
    `${BASE}/resumes/uploads/presign`,
    JSON.stringify({ resumeName: 'load-test', filename: 'resume.pdf', fileSize: PDF.length }),
    { headers: { ...auth, 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }, tags: { ep: 'presign' } },
  );
  if (!checkOk(presign, 'presign')) {
    return;
  }
  const { uploadId, uploadUrl, requiredHeaders } = presign.json('data');

  // 直接 PUT 到 S3，不带后端的 Authorization 头。
  const put = http.put(uploadUrl, PDF, { headers: requiredHeaders, tags: { ep: 's3_put' } });
  if (!check(put, { 's3 put 200': (r) => r.status === 200 })) {
    return;
  }

  const complete = http.post(`${BASE}/resumes/uploads/${uploadId}/complete`, null, {
    headers: auth,
    tags: { ep: ENDPOINT },
  });
  checkOk(complete, ENDPOINT);
}

export function handleSummary(data) {
  return writeSummary(data, ENDPOINT);
}
