import { api } from "./typed";
import type { components } from "./schema";

type Schemas = components["schemas"];

export type Match = Schemas["MatchDTO"];
export type MatchStatus = NonNullable<Match["status"]>;

/**
 * 发起匹配。结果已经存在时返回 COMPLETED；否则返回 PENDING / PROCESSING，需要轮询 getMatch。
 */
export function createMatch(body: Schemas["MatchCreateRequest"]) {
    return api("post", "/matches", { body });
}

export function getMatch(id: number) {
    return api("get", "/matches/{id}", { path: { id } });
}

export function listMatches(jobId: number, cursor?: number, size = 10) {
    return api("get", "/matches", { query: { jobId, cursor, size } });
}
