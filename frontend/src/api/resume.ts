import { api } from "./typed";
import type { components } from "./schema";

type Schemas = components["schemas"];

export type ResumeSummary = Schemas["ResumeSummaryDTO"];
export type ResumeDetail = Schemas["ResumeDTO"];
export type UploadTask = Schemas["ResumeUploadStatusResponse"];
export type UploadStatus = NonNullable<UploadTask["status"]>;

export function listResumes(page = 0, size = 10) {
    return api("get", "/resumes", { query: { page, size } });
}

export function getResumeById(id: number) {
    return api("get", "/resumes/{id}", { path: { id } });
}

export function listResumeVersions(resumeId: number) {
    return api("get", "/resumes/{resumeId}/versions", { path: { resumeId } });
}

/**
 * 申请 S3 预签名上传地址。同一次上传重试时复用同一个 idempotencyKey。
 */
export function presignResumeUpload(body: Schemas["PresignResumeUploadRequest"], idempotencyKey: string) {
    return api("post", "/resumes/uploads/presign", { body, headers: { "Idempotency-Key": idempotencyKey } });
}

/**
 * 文件已经 PUT 到 S3 后调用；后台处理完成前返回 202。
 */
export function completeResumeUpload(uploadId: string) {
    return api("post", "/resumes/uploads/{uploadId}/complete", { path: { uploadId } });
}

export function getResumeUploadStatus(uploadId: string) {
    return api("get", "/resumes/uploads/{uploadId}", { path: { uploadId } });
}
