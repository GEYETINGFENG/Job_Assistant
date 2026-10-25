import { describe, expect, it } from "vitest";
import { ApiError, ERROR_MESSAGES, toApiError } from "../errors";

describe("error code mapping", () => {
  it("covers every backend error code exactly once", () => {
    // 与后端 ErrorCode 枚举保持一致；后端新增错误码时这里也要加映射。
    expect(Object.keys(ERROR_MESSAGES).map(Number).sort()).toEqual([
      40000, 40001, 40002, 40100, 40101, 40300, 40400, 40900, 40901, 40902, 40903, 40904, 42900, 50000
    ]);
  });

  it("falls back to the HTTP status when the code is unknown", () => {
    expect(new ApiError({ kind: "http", status: 403, code: 49999 }).message).toBe(ERROR_MESSAGES[40300]);
    expect(new ApiError({ kind: "http", status: 502 }).message).toBe(ERROR_MESSAGES[50000]);
  });

  it("never shows the raw backend message to the user", () => {
    const error = toApiError({
      response: { status: 400, data: { code: 40000, message: "Invalid request parameters", description: "Page size must be between 1 and 100" } }
    });

    expect(error.message).toBe(ERROR_MESSAGES[40000]);
    expect(error.detail).toBe("Page size must be between 1 and 100");
  });

  it("classifies canceled requests separately", () => {
    expect(toApiError({ code: "ERR_CANCELED" }).kind).toBe("canceled");
  });

  it("marks network, timeout and 5xx errors as generic", () => {
    expect(new ApiError({ kind: "network" }).isGeneric).toBe(true);
    expect(new ApiError({ kind: "timeout" }).isGeneric).toBe(true);
    expect(new ApiError({ kind: "http", status: 503 }).isGeneric).toBe(true);
    expect(new ApiError({ kind: "http", status: 409, code: 40903 }).isGeneric).toBe(false);
  });
});
