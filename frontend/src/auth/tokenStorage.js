const KEYS = Object.freeze({
  accessToken: "accessToken",
  refreshToken: "refreshToken",
  tokenType: "tokenType",
  user: "currentUser"
});

/**
 * Token 的读写只经过这里：axios 拦截器、刷新逻辑、路由守卫和 auth store 读的是同一份数据，
 * 刷新后不会出现某一处还拿着旧 token 的情况。
 * storage 可以注入，测试时传入内存实现；不传时在调用时才读取 window.localStorage。
 */
export function createTokenStorage(injected) {
  const storage = {
    getItem: key => (injected ?? window.localStorage).getItem(key),
    setItem: (key, value) => (injected ?? window.localStorage).setItem(key, value),
    removeItem: key => (injected ?? window.localStorage).removeItem(key)
  };

  return {
    getAccessToken: () => storage.getItem(KEYS.accessToken) || "",
    getRefreshToken: () => storage.getItem(KEYS.refreshToken) || "",
    getTokenType: () => storage.getItem(KEYS.tokenType) || "Bearer",

    getUser() {
      try {
        return JSON.parse(storage.getItem(KEYS.user)) || null;
      } catch {
        return null;
      }
    },

    /**
     * access token 过期后仍然可以靠 refresh token 静默续期，所以只要有任意一个就算有会话。
     */
    hasSession() {
      return Boolean(storage.getItem(KEYS.accessToken) || storage.getItem(KEYS.refreshToken));
    },

    /** 保存登录或刷新返回的 token；登录响应额外带 user。 */
    saveTokens({ accessToken, refreshToken, tokenType, user }) {
      storage.setItem(KEYS.accessToken, accessToken);
      storage.setItem(KEYS.refreshToken, refreshToken);
      storage.setItem(KEYS.tokenType, tokenType || "Bearer");
      if (user !== undefined) storage.setItem(KEYS.user, JSON.stringify(user));
    },

    clear() {
      Object.values(KEYS).forEach(key => storage.removeItem(key));
    }
  };
}

export const tokenStorage = createTokenStorage();
