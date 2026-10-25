import axios from "axios";
import { ApiError, toApiError } from "./errors";
import { createTokenRefresher } from "../auth/tokenRefresher";

// 这些接口自身的 401 表示“账号密码错误 / refresh token 无效”，不能再触发刷新，否则会死循环。
const AUTH_ENDPOINTS = ["/user/login", "/user/register", "/user/refresh", "/user/logout"];

function isAuthEndpoint(url = "") {
  return AUTH_ENDPOINTS.some(endpoint => url.endsWith(endpoint));
}

function bearer(tokenStorage, accessToken) {
  return `${tokenStorage.getTokenType()} ${accessToken}`;
}

/**
 * 创建带统一错误处理和静默刷新的 axios 实例。
 *
 * 错误分层：
 *  - 这里统一处理：401 静默刷新并重放；网络 / 超时 / 5xx 交给 onGenericError 全局提示；
 *    所有错误都转换成 ApiError，message 已经是映射好的用户提示。
 *  - 页面只处理本页面特有的语义（例如 404 时清空详情、409 时提示刷新）。
 *
 * @param options.baseURL          接口前缀
 * @param options.tokenStorage     token 读写
 * @param options.onSessionExpired refresh 也失效时调用：清理会话并跳转登录
 * @param options.onGenericError   网络 / 超时 / 5xx 的全局提示
 * @param options.adapter          测试时注入的 axios adapter
 */
export function createApiClient({ baseURL, tokenStorage, onSessionExpired = () => {}, onGenericError = () => {}, adapter, timeout = 10000 }) {
  const client = axios.create({ baseURL, timeout, adapter });
  // 刷新请求使用不带拦截器的独立实例，刷新本身失败时不会再次进入 401 处理。
  const refreshClient = axios.create({ baseURL, timeout, adapter });

  const refresher = createTokenRefresher(async () => {
    const refreshToken = tokenStorage.getRefreshToken();
    if (!refreshToken) throw new ApiError({ kind: "http", status: 401, code: 40100 });

    try {
      const response = await refreshClient.post("/user/refresh", { refreshToken });
      if (response.data?.code !== 0) throw toApiError({ response });
      tokenStorage.saveTokens(response.data.data);
      return response.data.data.accessToken;
    } catch (error) {
      throw toApiError(error);
    }
  });

  client.interceptors.request.use(config => {
    const accessToken = tokenStorage.getAccessToken();
    if (accessToken && !config.headers.Authorization) config.headers.Authorization = bearer(tokenStorage, accessToken);
    return config;
  });

  client.interceptors.response.use(
    response => {
      // 后端约定 code = 0 表示成功；HTTP 2xx 但 code 非 0 也按错误处理。
      if (response.data && typeof response.data.code === "number" && response.data.code !== 0) {
        return Promise.reject(toApiError({ response }));
      }
      return response;
    },
    async error => {
      const config = error.config;

      if (error.response?.status === 401 && config && !config._retried && !isAuthEndpoint(config.url)) {
        config._retried = true;
        const usedToken = config.headers?.Authorization;

        let accessToken;
        try {
          // 其他请求已经刷新过：直接用新 token 重放，不再发起刷新。
          const current = tokenStorage.getAccessToken();
          accessToken = current && bearer(tokenStorage, current) !== usedToken
            ? current
            : await refresher.refreshAccessToken();
        } catch (refreshError) {
          const apiError = toApiError(refreshError);
          // refresh 被明确拒绝才登出；网络错误时保留会话，用户恢复网络后还能继续。
          if (apiError.kind === "http") onSessionExpired(apiError);
          else onGenericError(apiError);
          throw apiError;
        }

        config.headers.Authorization = bearer(tokenStorage, accessToken);
        return client(config);
      }

      const apiError = toApiError(error);
      if (apiError.status === 401 && !isAuthEndpoint(config?.url)) onSessionExpired(apiError);
      else if (apiError.isGeneric) onGenericError(apiError);
      throw apiError;
    }
  );

  return client;
}
