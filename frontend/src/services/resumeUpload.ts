import { completeResumeUpload, getResumeUploadStatus, presignResumeUpload } from "../api/resume";
import type { UploadStatus, UploadTask } from "../api/resume";
import { ApiError } from "../api/errors";
import { pollWithBackoff } from "../utils/pollWithBackoff";

export const MAX_RESUME_SIZE = 10 * 1024 * 1024;
const ALLOWED_EXTENSIONS = [".pdf", ".docx"];

/** 后端上传任务的终态：COMPLETED 成功；DEAD（重试耗尽）和 EXPIRED（会话过期）失败。 */
export const TERMINAL_STATUSES: readonly UploadStatus[] = ["COMPLETED", "DEAD", "EXPIRED"];

export type UploadPhase = "requesting" | "uploading" | "processing";

/**
 * 前端预校验只是体验优化（尽早提示），真正的校验在后端：Tika 检测真实类型、zip bomb 防御等。
 */
export function validateResumeFile(file: File | null): string {
  if (!file) return "Choose a PDF or DOCX file.";
  const name = file.name.toLowerCase();
  if (!ALLOWED_EXTENSIONS.some(extension => name.endsWith(extension))) return "Only PDF and DOCX files are supported.";
  if (file.size === 0) return "The file is empty.";
  if (file.size > MAX_RESUME_SIZE) return "The file must be 10 MB or smaller.";
  return "";
}

const STORAGE_UPLOAD_FAILED = "Couldn't upload the file to storage. Please try again.";

/**
 * 把文件直接 PUT 到 S3。不经过 axios 实例：S3 请求不能带我们的 Authorization 头。
 */
async function putToS3(uploadUrl: string, file: File, requiredHeaders: Record<string, string>, signal?: AbortSignal) {
  let response: Response;
  try {
    response = await fetch(uploadUrl, { method: "PUT", headers: requiredHeaders, body: file, signal });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    // 浏览器把 S3 的 CORS 拒绝也报告为网络错误。
    throw new ApiError({ kind: "network", detail: String(error), message: STORAGE_UPLOAD_FAILED });
  }
  if (!response.ok) {
    throw new ApiError({ kind: "http", status: response.status, message: STORAGE_UPLOAD_FAILED });
  }
}

interface UploadOptions {
  file: File;
  resumeName: string;
  idempotencyKey: string;
  onPhase?: (phase: UploadPhase) => void;
  onStatus?: (task: UploadTask) => void;
  signal?: AbortSignal;
}

/**
 * 完整上传流程：预签名 -> 直传 S3 -> 确认完成 -> 指数退避轮询处理状态。
 * 返回终态的任务；调用方根据 status 区分 COMPLETED / DEAD / EXPIRED。
 */
export async function uploadResume({ file, resumeName, idempotencyKey, onPhase = () => {}, onStatus = () => {}, signal }: UploadOptions): Promise<UploadTask> {
  onPhase("requesting");
  const presign = await presignResumeUpload({ resumeName, filename: file.name, fileSize: file.size }, idempotencyKey);
  if (!presign.uploadId || !presign.uploadUrl) throw new ApiError({ kind: "http", detail: "Presign response is incomplete" });

  onPhase("uploading");
  await putToS3(presign.uploadUrl, file, presign.requiredHeaders ?? {}, signal);

  onPhase("processing");
  const completed = await completeResumeUpload(presign.uploadId);
  if (completed.status === "COMPLETED") {
    onStatus(completed);
    return completed;
  }

  return waitForUpload(presign.uploadId, { onStatus, signal });
}

/**
 * 轮询上传任务直到终态。轮询超时后用户可以再次调用继续等待。
 */
export function waitForUpload(
  uploadId: string,
  { onStatus = () => {}, signal, timeout = 120000 }: { onStatus?: (task: UploadTask) => void; signal?: AbortSignal; timeout?: number } = {}
): Promise<UploadTask> {
  return pollWithBackoff({
    fetchStatus: () => getResumeUploadStatus(uploadId),
    isDone: (task: UploadTask) => task.status !== undefined && TERMINAL_STATUSES.includes(task.status),
    // 网络抖动 / 超时 / 5xx 继续轮询；404、403 等直接停止。
    shouldRetryError: (error: unknown) => error instanceof ApiError && error.isGeneric,
    onUpdate: onStatus,
    signal,
    timeout
  });
}
