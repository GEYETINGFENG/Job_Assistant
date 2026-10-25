/**
 * 后端 ErrorCode -> 用户提示。
 * 这是前端错误文案的单一事实来源：页面不直接展示后端 message（技术性、英文、可能变化），
 * 后端新增错误码时只需要在这里加一行。
 */
export const ERROR_MESSAGES = Object.freeze({
  40000: "Some of the information you entered is invalid.",
  40001: "Required information is missing.",
  40002: "This status change is not allowed.",
  40100: "Your session has expired. Please sign in again.",
  40101: "Incorrect account or password.",
  40300: "You don't have permission to do this.",
  40400: "The item you're looking for doesn't exist or was removed.",
  40900: "This account already exists.",
  40901: "This upload is no longer in progress. Please start a new upload.",
  40902: "This application was just updated elsewhere. Refresh and try again.",
  40903: "This item was just modified elsewhere. Refresh and try again.",
  40904: "This conflicts with existing data.",
  50000: "Something went wrong on our side. Please try again later."
});

const KIND_MESSAGES = Object.freeze({
  network: "Can't reach the server. Check your connection and try again.",
  timeout: "The server took too long to respond. Please try again.",
  canceled: "The request was canceled."
});

const FALLBACK_MESSAGE = "Something went wrong. Please try again.";

/**
 * 统一的接口错误对象。
 * kind:
 *  - network  没有收到响应（断网、跨域失败、服务未启动）
 *  - timeout  请求超时
 *  - canceled 请求被主动取消（例如组件卸载）
 *  - http     收到了带错误状态码或非 0 code 的响应
 */
export class ApiError extends Error {
  /**
   * @param {{ kind: "network" | "timeout" | "canceled" | "http", status?: number | null, code?: number | null, detail?: string, message?: string }} options
   */
  constructor({ kind, status = null, code = null, detail = "", message }) {
    super(message ?? resolveMessage(kind, status, code));
    this.name = "ApiError";
    this.kind = kind;
    this.status = status;
    this.code = code;
    // 后端 description / message，只用于排查问题，不直接展示给用户。
    this.detail = detail;
  }

  /** 网络、超时、5xx 属于通用错误，由全局提示兜底，页面不需要逐个处理。 */
  get isGeneric() {
    return this.kind === "network" || this.kind === "timeout" || (this.status !== null && this.status >= 500);
  }
}

function resolveMessage(kind, status, code) {
  if (code !== null && ERROR_MESSAGES[code]) return ERROR_MESSAGES[code];
  if (KIND_MESSAGES[kind]) return KIND_MESSAGES[kind];
  if (status === 401) return ERROR_MESSAGES[40100];
  if (status === 403) return ERROR_MESSAGES[40300];
  if (status === 404) return ERROR_MESSAGES[40400];
  if (status !== null && status >= 500) return ERROR_MESSAGES[50000];
  return FALLBACK_MESSAGE;
}

/**
 * 把 axios 错误或 code != 0 的响应转换成 ApiError。
 */
export function toApiError(error) {
  if (error instanceof ApiError) return error;

  if (error?.code === "ERR_CANCELED") return new ApiError({ kind: "canceled" });
  if (error?.code === "ECONNABORTED" || error?.code === "ETIMEDOUT") return new ApiError({ kind: "timeout" });

  const response = error?.response;
  if (!response) return new ApiError({ kind: "network", detail: error?.message ?? "" });

  const body = response.data ?? {};
  const code = typeof body.code === "number" ? body.code : null;
  return new ApiError({
    kind: "http",
    status: response.status,
    code,
    detail: body.description || body.message || ""
  });
}
