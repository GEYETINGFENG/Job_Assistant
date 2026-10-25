import { defineStore } from "pinia";
import { tokenStorage } from "../auth/tokenStorage";

/**
 * 只保存界面需要的用户信息；token 统一由 tokenStorage 管理，
 * 静默刷新替换 token 时不需要同步这里。
 */
export const useAuthStore = defineStore("auth", {
  state: () => ({
    user: tokenStorage.getUser()
  }),
  actions: {
    setLoginData(loginData) {
      tokenStorage.saveTokens(loginData);
      this.user = loginData.user || null;
    },
    clearAuth() {
      tokenStorage.clear();
      this.user = null;
    }
  }
});
