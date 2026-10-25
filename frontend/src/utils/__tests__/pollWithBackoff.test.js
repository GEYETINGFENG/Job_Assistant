import { describe, expect, it, vi } from "vitest";
import { pollWithBackoff, PollTimeoutError } from "../pollWithBackoff";

/** 虚拟时钟：sleep 不真的等待，只推进时间并记录每次等待的时长。 */
function virtualClock() {
  let time = 0;
  const delays = [];
  return {
    delays,
    now: () => time,
    sleep: async ms => {
      delays.push(ms);
      time += ms;
    }
  };
}

function statuses(...values) {
  let index = 0;
  return vi.fn(async () => values[Math.min(index++, values.length - 1)]);
}

const isTerminal = status => ["COMPLETED", "DEAD", "EXPIRED"].includes(status);

describe("pollWithBackoff", () => {
  it("backs off exponentially and caps the delay", async () => {
    const clock = virtualClock();
    const fetchStatus = statuses("PENDING", "PROCESSING", "PROCESSING", "PROCESSING", "PROCESSING", "PROCESSING", "COMPLETED");

    const result = await pollWithBackoff({ fetchStatus, isDone: isTerminal, ...clock });

    expect(result).toBe("COMPLETED");
    expect(clock.delays).toEqual([1000, 2000, 4000, 8000, 8000, 8000]);
    expect(fetchStatus).toHaveBeenCalledTimes(7);
  });

  it("stops on DEAD instead of polling forever", async () => {
    const clock = virtualClock();
    const fetchStatus = statuses("PENDING", "PROCESSING", "DEAD");

    await expect(pollWithBackoff({ fetchStatus, isDone: isTerminal, ...clock })).resolves.toBe("DEAD");
    expect(fetchStatus).toHaveBeenCalledTimes(3);
  });

  it("gives up after the total timeout", async () => {
    const clock = virtualClock();
    const fetchStatus = statuses("PROCESSING");

    await expect(pollWithBackoff({ fetchStatus, isDone: isTerminal, timeout: 20000, ...clock }))
      .rejects.toBeInstanceOf(PollTimeoutError);
    // 1 + 2 + 4 + 8 = 15 秒后，下一次再等 8 秒会超过 20 秒，于是停止。
    expect(clock.delays).toEqual([1000, 2000, 4000, 8000]);
    expect(clock.now()).toBeLessThanOrEqual(20000);
  });

  it("reports every status to onUpdate", async () => {
    const clock = virtualClock();
    const onUpdate = vi.fn();

    await pollWithBackoff({ fetchStatus: statuses("PENDING", "COMPLETED"), isDone: isTerminal, onUpdate, ...clock });

    expect(onUpdate.mock.calls.map(call => call[0])).toEqual(["PENDING", "COMPLETED"]);
  });

  it("keeps polling through retryable errors", async () => {
    const clock = virtualClock();
    const networkError = Object.assign(new Error("offline"), { kind: "network" });
    const fetchStatus = vi.fn()
      .mockRejectedValueOnce(networkError)
      .mockResolvedValueOnce("COMPLETED");

    const result = await pollWithBackoff({
      fetchStatus,
      isDone: isTerminal,
      shouldRetryError: error => error.kind === "network",
      ...clock
    });

    expect(result).toBe("COMPLETED");
    expect(fetchStatus).toHaveBeenCalledTimes(2);
  });

  it("stops immediately on non-retryable errors", async () => {
    const clock = virtualClock();
    const notFound = Object.assign(new Error("not found"), { kind: "http", status: 404 });
    const fetchStatus = vi.fn().mockRejectedValue(notFound);

    await expect(pollWithBackoff({ fetchStatus, isDone: isTerminal, ...clock })).rejects.toBe(notFound);
    expect(fetchStatus).toHaveBeenCalledTimes(1);
  });

  it("stops when aborted", async () => {
    const controller = new AbortController();
    const fetchStatus = vi.fn(async () => {
      controller.abort();
      return "PROCESSING";
    });

    await expect(pollWithBackoff({ fetchStatus, isDone: isTerminal, signal: controller.signal, initialDelay: 10 }))
      .rejects.toMatchObject({ name: "AbortError" });
    expect(fetchStatus).toHaveBeenCalledTimes(1);
  });
});
