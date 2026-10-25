import { createApiClient } from "./apiClient";
import { tokenStorage } from "../auth/tokenStorage";

let globalErrorHandler = () => {};
let redirectingToLogin = false;

/**
 * 由 main.js 注册全局提示（toast）。request 本身不依赖 UI 组件。
 */
export function setGlobalErrorHandler(handler) {
  globalErrorHandler = handler;
}

const request = createApiClient({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  tokenStorage,
  onGenericError: error => globalErrorHandler(error),
  onSessionExpired() {
    tokenStorage.clear();
    // 多个请求同时失效时只跳转一次。
    if (redirectingToLogin || window.location.pathname === "/login") return;
    redirectingToLogin = true;
    const redirect = encodeURIComponent(window.location.pathname + window.location.search);
    window.location.assign(`/login?redirect=${redirect}&expired=1`);
  }
});

export default request;
