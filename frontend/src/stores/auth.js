import { defineStore } from "pinia";

function readStoredUser() {
  try {
    return JSON.parse(localStorage.getItem("currentUser")) || null;
  } catch {
    return null;
  }
}

export const useAuthStore = defineStore("auth", {
  state: () => ({
    accessToken: localStorage.getItem("accessToken") || "",
    tokenType: localStorage.getItem("tokenType") || "Bearer",
    user: readStoredUser()
  }),
  getters: {
    isAuthenticated: state => Boolean(state.accessToken)
  },
  actions: {
    setLoginData(loginData) {
      this.accessToken = loginData.accessToken;
      this.tokenType = loginData.tokenType || "Bearer";
      this.user = loginData.user || null;

      localStorage.setItem("accessToken", this.accessToken);
      localStorage.setItem("tokenType", this.tokenType);

      if (this.user) localStorage.setItem("currentUser", JSON.stringify(this.user));
      else localStorage.removeItem("currentUser");
    },
    clearAuth() {
      this.accessToken = "";
      this.tokenType = "Bearer";
      this.user = null;

      localStorage.removeItem("accessToken");
      localStorage.removeItem("tokenType");
      localStorage.removeItem("currentUser");
    }
  }
});