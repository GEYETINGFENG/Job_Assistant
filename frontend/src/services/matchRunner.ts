import { createMatch, getMatch } from "../api/match";
import type { Match, MatchStatus } from "../api/match";
import { ApiError } from "../api/errors";
import { pollWithBackoff } from "../utils/pollWithBackoff";

/** 匹配任务的终态：COMPLETED 有结果；DEAD 失败（不可重试的错误或重试耗尽），可以重新发起。 */
export const TERMINAL_MATCH_STATUSES: readonly MatchStatus[] = ["COMPLETED", "DEAD"];

export function isTerminalMatch(match: Match): boolean {
    return match.status !== undefined && TERMINAL_MATCH_STATUSES.includes(match.status);
}

interface RunMatchOptions {
    resumeVersionId: number;
    jobId: number;
    onStatus?: (match: Match) => void;
    signal?: AbortSignal;
    timeout?: number;
}

/**
 * 发起匹配并等待终态：已有结果时直接返回（后端返回 200）；否则按指数退避轮询（后端返回 202）。
 * 同一简历版本 + 同一 JD 重复发起，后端返回同一条任务，不会重复分析。
 */
export async function runMatch({ resumeVersionId, jobId, onStatus = () => {}, signal, timeout }: RunMatchOptions): Promise<Match> {
    const created = await createMatch({ resumeVersionId, jobId });
    onStatus(created);
    if (isTerminalMatch(created) || created.id === undefined) return created;
    return waitForMatch(created.id, { onStatus, signal, timeout });
}

/**
 * 轮询匹配任务直到终态；轮询超时后可以再次调用继续等待。
 */
export function waitForMatch(
    matchId: number,
    { onStatus = () => {}, signal, timeout = 120000 }: { onStatus?: (match: Match) => void; signal?: AbortSignal; timeout?: number } = {}
): Promise<Match> {
    return pollWithBackoff({
        fetchStatus: () => getMatch(matchId),
        isDone: isTerminalMatch,
        // 网络抖动 / 超时 / 5xx 继续轮询；404 等直接停止。
        shouldRetryError: (error: unknown) => error instanceof ApiError && error.isGeneric,
        onUpdate: onStatus,
        signal,
        timeout
    });
}
