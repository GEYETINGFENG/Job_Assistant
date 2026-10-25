import { api } from "./typed";
import type { components } from "./schema";

type Schemas = components["schemas"];

export type Job = Schemas["JobDTO"];
export type JobCreateRequest = Schemas["JobCreateRequest"];

export function createJob(body: JobCreateRequest) {
    return api("post", "/jobs", { body });
}

/**
 * 游标分页：第一页不传 cursor，之后传上一页返回的 nextCursor。
 */
export function listJobs(cursor?: number, size = 10) {
    return api("get", "/jobs", { query: { cursor, size } });
}

export function getJob(id: number) {
    return api("get", "/jobs/{id}", { path: { id } });
}
