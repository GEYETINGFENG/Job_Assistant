import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../../api/match", () => ({
  createMatch: vi.fn(),
  getMatch: vi.fn()
}));
// 轮询不真的等待：sleep 立即返回。
vi.mock("../../utils/pollWithBackoff", async importOriginal => {
  const original = await importOriginal();
  return {
    ...original,
    pollWithBackoff: options => original.pollWithBackoff({ ...options, sleep: async () => {} })
  };
});

const { createMatch, getMatch } = await import("../../api/match");
const { runMatch } = await import("../matchRunner");
const { ApiError } = await import("../../api/errors");

describe("runMatch", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns an existing completed result without polling", async () => {
    createMatch.mockResolvedValue({ id: 1, status: "COMPLETED", score: 80 });

    const result = await runMatch({ resumeVersionId: 11, jobId: 3 });

    expect(result.score).toBe(80);
    expect(getMatch).not.toHaveBeenCalled();
  });

  it("polls an accepted task until it completes", async () => {
    createMatch.mockResolvedValue({ id: 1, status: "PENDING" });
    getMatch
      .mockResolvedValueOnce({ id: 1, status: "PROCESSING" })
      .mockResolvedValueOnce({ id: 1, status: "COMPLETED", score: 67 });
    const statuses = [];

    const result = await runMatch({ resumeVersionId: 11, jobId: 3, onStatus: match => statuses.push(match.status) });

    expect(result.score).toBe(67);
    expect(statuses).toEqual(["PENDING", "PROCESSING", "COMPLETED"]);
    expect(getMatch).toHaveBeenCalledWith(1);
  });

  it("stops polling on DEAD", async () => {
    createMatch.mockResolvedValue({ id: 1, status: "PENDING" });
    getMatch.mockResolvedValue({ id: 1, status: "DEAD", lastErrorCode: "LLM_REJECTED" });

    const result = await runMatch({ resumeVersionId: 11, jobId: 3 });

    expect(result.status).toBe("DEAD");
    expect(getMatch).toHaveBeenCalledTimes(1);
  });

  it("keeps polling through temporary network errors", async () => {
    createMatch.mockResolvedValue({ id: 1, status: "PENDING" });
    getMatch
      .mockRejectedValueOnce(new ApiError({ kind: "network" }))
      .mockResolvedValueOnce({ id: 1, status: "COMPLETED", score: 50 });

    await expect(runMatch({ resumeVersionId: 11, jobId: 3 })).resolves.toMatchObject({ score: 50 });
  });

  it("surfaces quota errors from createMatch", async () => {
    createMatch.mockRejectedValue(new ApiError({ kind: "http", status: 429, code: 42900 }));

    await expect(runMatch({ resumeVersionId: 11, jobId: 3 })).rejects.toMatchObject({
      code: 42900,
      message: "You've reached today's limit. Please try again tomorrow."
    });
  });
});
