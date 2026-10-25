/**
 * 单飞（single-flight）刷新：同一时刻最多只有一个刷新请求在飞。
 *
 * 为什么必须加锁：后端 refresh token 是一次性的（rotation），旧 token 用过就作废，
 * 再次出现会触发 reuse detection 并撤销整个 token family。
 * 5 个请求同时 401 时如果各自去刷新，第 2~5 个会拿着已作废的 refresh token，
 * 把正常用户当成攻击者踢下线。
 *
 * refresh: 真正调用刷新接口的函数，成功时返回新的 access token。
 */
export function createTokenRefresher(refresh) {
  let inFlight = null;

  return {
    refreshAccessToken() {
      if (!inFlight) {
        inFlight = Promise.resolve()
          .then(refresh)
          .finally(() => {
            inFlight = null;
          });
      }
      return inFlight;
    },

    get isRefreshing() {
      return inFlight !== null;
    }
  };
}
