<script setup>
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { userLogin } from "../api/user";
import { useAuthStore } from "../stores/auth";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const form = reactive({
  userAccount: "",
  userPassword: ""
});
const loading = ref(false);
const errorMessage = ref("");
// refresh token 也失效、被拦截器送回登录页时给出说明，而不是一声不响地跳转。
const sessionExpired = route.query.expired === "1";

function safeRedirect(target) {
  // 只允许站内路径，防止 ?redirect=https://evil.example 这类开放重定向。
  return typeof target === "string" && target.startsWith("/") && !target.startsWith("//") ? target : "/dashboard";
}

async function handleLogin() {
  errorMessage.value = "";

  if (!form.userAccount.trim() || !form.userPassword) {
    errorMessage.value = "User account and password are required";
    return;
  }

  loading.value = true;

  try {
    const loginData = await userLogin({
      userAccount: form.userAccount.trim(),
      userPassword: form.userPassword
    });

    authStore.setLoginData(loginData);
    await router.replace(safeRedirect(route.query.redirect));
  } catch (error) {
    // 网络 / 5xx 已由全局提示处理；这里展示账号密码错误等本页面语义。
    if (!error.isGeneric) errorMessage.value = error.message;
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card">
      <div class="brand">
        <span class="wordmark">JobAssistant</span>
        <h1>Sign in</h1>
        <p>Manage resumes, jobs and applications in one place.</p>
      </div>

      <form class="login-form" @submit.prevent="handleLogin">
        <div class="field-group">
          <label class="field">
            <span>Account</span>
            <input id="userAccount" v-model="form.userAccount" type="text" autocomplete="username" placeholder="Account">
          </label>
          <label class="field">
            <span>Password</span>
            <input id="userPassword" v-model="form.userPassword" type="password" autocomplete="current-password" placeholder="Password">
          </label>
        </div>

        <p v-if="sessionExpired && !errorMessage" class="info-message">Your session has expired. Please sign in again.</p>
        <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>

        <button class="submit" type="submit" :disabled="loading">{{ loading ? "Signing in…" : "Sign In" }}</button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px 16px;
}

.login-card {
  width: 100%;
  max-width: 400px;
}

.brand {
  margin-bottom: 32px;
  text-align: center;
}

.wordmark {
  color: var(--text-secondary);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.brand h1 {
  margin: 10px 0 0;
  font-size: 40px;
  font-weight: 600;
  letter-spacing: -0.03em;
}

.brand p {
  margin: 10px 0 0;
  color: var(--text-secondary);
  font-size: 17px;
  line-height: 1.45;
}

.login-form {
  display: grid;
  gap: 16px;
}

/* 两个输入框拼成一组，中间一条细线，和 Apple 账号登录页一致。 */
.field-group {
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: var(--radius-control);
}

.field {
  display: block;
}

.field + .field {
  border-top: 1px solid var(--hairline);
}

.field span {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

.field input {
  width: 100%;
  height: 56px;
  padding: 0 16px;
  color: var(--text);
  font-size: 17px;
  background: transparent;
  border: 0;
  outline: none;
}

.field input::placeholder {
  color: var(--text-tertiary);
}

.field:focus-within {
  position: relative;
  box-shadow: inset 0 0 0 2px var(--accent);
}

.submit {
  height: 50px;
  color: #ffffff;
  font-size: 17px;
  font-weight: 500;
  background: var(--accent);
  border: 0;
  border-radius: var(--radius-pill);
  transition: background 0.2s, opacity 0.2s;
}

.submit:hover:not(:disabled) {
  background: var(--accent-hover);
}

.submit:focus-visible {
  outline: none;
  box-shadow: 0 0 0 4px var(--focus-ring);
}

.submit:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.info-message, .error-message {
  margin: 0;
  font-size: 14px;
  line-height: 1.45;
  text-align: center;
}

.info-message {
  color: var(--text-secondary);
}

.error-message {
  color: var(--danger);
}
</style>
