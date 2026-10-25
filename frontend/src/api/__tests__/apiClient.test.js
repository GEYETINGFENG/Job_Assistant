import { describe, expect, it, vi } from "vitest";
import { AxiosError } from "axios";
import { createApiClient } from "../apiClient";
import { createTokenStorage } from "../../auth/tokenStorage";

function memoryStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem: key => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: key => map.delete(key)
  };
}

/**
 * 模拟后端：access token 只有一个有效值；refresh token 一次性（rotation），
 * 旧 refresh token 再次出现视为复用并撤销整个 family。
 */
function fakeBackend({ accessToken = "A2", refreshToken = "R1" } = {}) {
  const state = { accessToken, refreshToken, generation: 1, refreshCalls: 0, reuseDetected: false, revoked: false };

  function respond(config, status, body) {
    const response = { data: body, status, statusText: String(status), headers: {}, config };
    if (status >= 200 && status < 300) return response;
    throw new AxiosError(`Request failed with status code ${status}`, "ERR_BAD_RESPONSE", config, {}, response);
  }

  async function adapter(config) {
    // 让并发请求真正同时处于“在飞”状态。
    await new Promise(resolve => setTimeout(resolve, 5));

    if (config.url.endsWith("/user/refresh")) {
      state.refreshCalls += 1;
      const { refreshToken: submitted } = JSON.parse(config.data);
      if (state.revoked || submitted !== state.refreshToken) {
        state.reuseDetected = true;
        state.revoked = true;
        return respond(config, 401, { code: 40100, message: "Unauthenticated" });
      }
      state.generation += 1;
      state.accessToken = `A${state.generation + 1}`;
      state.refreshToken = `R${state.generation}`;
      return respond(config, 200, {
        code: 0,
        data: { accessToken: state.accessToken, refreshToken: state.refreshToken, tokenType: "Bearer" }
      });
    }

    if (config.url.endsWith("/user/login")) {
      return respond(config, 401, { code: 40101, message: "User account or password is incorrect" });
    }

    if (config.headers.Authorization !== `Bearer ${state.accessToken}`) {
      return respond(config, 401, { code: 40100, message: "Unauthenticated" });
    }
    return respond(config, 200, { code: 0, data: { url: config.url } });
  }

  return { state, adapter };
}

function setup({ storage, backend = fakeBackend(), adapter } = {}) {
  const tokenStorage = createTokenStorage(storage ?? memoryStorage({ accessToken: "A-expired", refreshToken: "R1", tokenType: "Bearer" }));
  const onSessionExpired = vi.fn();
  const onGenericError = vi.fn();
  const client = createApiClient({
    baseURL: "http://api.test",
    tokenStorage,
    onSessionExpired,
    onGenericError,
    adapter: adapter ?? backend.adapter
  });
  return { client, tokenStorage, backend, onSessionExpired, onGenericError };
}

describe("silent token refresh", () => {
  it("refreshes an expired access token and replays the original request", async () => {
    const { client, tokenStorage, backend } = setup();

    const response = await client.get("/resumes");

    expect(response.data.data.url).toBe("/resumes");
    expect(backend.state.refreshCalls).toBe(1);
    expect(tokenStorage.getAccessToken()).toBe(backend.state.accessToken);
    expect(tokenStorage.getRefreshToken()).toBe(backend.state.refreshToken);
  });

  it("sends only one refresh request when several requests get 401 at the same time", async () => {
    const { client, backend, onSessionExpired } = setup();

    const responses = await Promise.all(
      ["/a", "/b", "/c", "/d", "/e"].map(url => client.get(url))
    );

    expect(responses.map(response => response.data.data.url)).toEqual(["/a", "/b", "/c", "/d", "/e"]);
    expect(backend.state.refreshCalls).toBe(1);
    // 不加锁时第 2~5 个刷新会提交已作废的 R1，触发 reuse detection 把用户踢下线。
    expect(backend.state.reuseDetected).toBe(false);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it("does not refresh again when another request already refreshed the token", async () => {
    const backend = fakeBackend();
    const { client, tokenStorage } = setup({ backend });

    await client.get("/first");
    expect(backend.state.refreshCalls).toBe(1);

    // 模拟一个用旧 token 发出、在刷新完成后才收到 401 的请求。
    const response = await client.get("/late", { headers: { Authorization: "Bearer A-expired" } });

    expect(response.data.data.url).toBe("/late");
    expect(backend.state.refreshCalls).toBe(1);
    expect(tokenStorage.getRefreshToken()).toBe("R2");
  });

  it("ends the session when the refresh token is rejected", async () => {
    const backend = fakeBackend({ refreshToken: "R-other" });
    const { client, onSessionExpired } = setup({ backend });

    await expect(client.get("/resumes")).rejects.toMatchObject({ name: "ApiError", status: 401, code: 40100 });
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it("ends the session without calling refresh when there is no refresh token", async () => {
    const backend = fakeBackend();
    const { client, onSessionExpired } = setup({ backend, storage: memoryStorage({ accessToken: "A-expired" }) });

    await expect(client.get("/resumes")).rejects.toMatchObject({ code: 40100 });
    expect(backend.state.refreshCalls).toBe(0);
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it("does not try to refresh when login itself returns 401", async () => {
    const { client, backend, onSessionExpired } = setup();

    await expect(client.post("/user/login", {})).rejects.toMatchObject({
      code: 40101,
      message: "Incorrect account or password."
    });
    expect(backend.state.refreshCalls).toBe(0);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it("keeps the session when refresh fails because of the network", async () => {
    let calls = 0;
    const adapter = async config => {
      calls += 1;
      if (config.url.endsWith("/user/refresh")) throw new AxiosError("Network Error", "ERR_NETWORK", config);
      const response = { data: { code: 40100 }, status: 401, statusText: "401", headers: {}, config };
      throw new AxiosError("401", "ERR_BAD_REQUEST", config, {}, response);
    };
    const { client, tokenStorage, onSessionExpired, onGenericError } = setup({ adapter });

    await expect(client.get("/resumes")).rejects.toMatchObject({ kind: "network" });
    expect(calls).toBe(2);
    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(onGenericError).toHaveBeenCalledTimes(1);
    expect(tokenStorage.getRefreshToken()).toBe("R1");
  });
});

describe("unified error handling", () => {
  function clientReturning(status, body) {
    const adapter = async config => {
      const response = { data: body, status, statusText: String(status), headers: {}, config };
      if (status < 300) return response;
      throw new AxiosError("failed", "ERR_BAD_RESPONSE", config, {}, response);
    };
    return setup({ adapter, storage: memoryStorage({ accessToken: "A" }) });
  }

  it("maps business error codes to user-facing messages without global toast", async () => {
    const { client, onGenericError } = clientReturning(409, {
      code: 40902,
      message: "Application status conflict",
      description: "Application status is no longer SCREENING, reload and try again"
    });

    const error = await client.patch("/applications/1", {}).catch(e => e);

    expect(error).toMatchObject({
      kind: "http",
      status: 409,
      code: 40902,
      message: "This application was just updated elsewhere. Refresh and try again.",
      detail: "Application status is no longer SCREENING, reload and try again"
    });
    expect(onGenericError).not.toHaveBeenCalled();
  });

  it("treats 2xx responses with a non-zero code as errors", async () => {
    const { client } = clientReturning(200, { code: 40000, message: "Invalid request parameters" });

    await expect(client.get("/resumes")).rejects.toMatchObject({ code: 40000, status: 200 });
  });

  it("reports server errors to the global handler", async () => {
    const { client, onGenericError } = clientReturning(500, { code: 50000, message: "Internal server error" });

    await expect(client.get("/resumes")).rejects.toMatchObject({ status: 500 });
    expect(onGenericError).toHaveBeenCalledTimes(1);
  });

  it("reports network failures to the global handler", async () => {
    const adapter = async config => {
      throw new AxiosError("Network Error", "ERR_NETWORK", config);
    };
    const { client, onGenericError } = setup({ adapter });

    await expect(client.get("/resumes")).rejects.toMatchObject({
      kind: "network",
      message: "Can't reach the server. Check your connection and try again."
    });
    expect(onGenericError).toHaveBeenCalledTimes(1);
  });

  it("reports timeouts to the global handler", async () => {
    const adapter = async config => {
      throw new AxiosError("timeout of 10000ms exceeded", "ECONNABORTED", config);
    };
    const { client, onGenericError } = setup({ adapter });

    await expect(client.get("/resumes")).rejects.toMatchObject({ kind: "timeout" });
    expect(onGenericError).toHaveBeenCalledTimes(1);
  });
});
