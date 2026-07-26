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

async function handleLogin() {
  errorMessage.value = "";

  if (!form.userAccount.trim() || !form.userPassword) {
    errorMessage.value = "User account and password are required";
    return;
  }

  loading.value = true;

  try {
    const response = await userLogin({
      userAccount: form.userAccount.trim(),
      userPassword: form.userPassword
    });

    if (response.data.code !== 0) {
      errorMessage.value = response.data.message || "Login failed";
      return;
    }

    authStore.setLoginData(response.data.data);
    await router.replace(route.query.redirect || "/dashboard");
  } catch (error) {
    errorMessage.value = error.response?.data?.message || "Unable to connect to the server";
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card">
      <div class="brand">
        <div class="brand-icon">J</div>
        <h1>JobAssistant</h1>
        <p>Manage resumes, jobs and applications in one place.</p>
      </div>

      <form class="login-form" @submit.prevent="handleLogin">
        <div class="field">
          <label for="userAccount">User Account</label>
          <input id="userAccount" v-model="form.userAccount" type="text" autocomplete="username" placeholder="Enter your account">
        </div>

        <div class="field">
          <label for="userPassword">Password</label>
          <input id="userPassword" v-model="form.userPassword" type="password" autocomplete="current-password" placeholder="Enter your password">
        </div>

        <p v-if="errorMessage" class="error-message">{{ errorMessage }}</p>

        <button type="submit" :disabled="loading">{{ loading ? "Signing in..." : "Sign In" }}</button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: linear-gradient(135deg, #eff6ff 0%, #f8fafc 45%, #eef2ff 100%);
}

.login-card {
  width: 100%;
  max-width: 410px;
  padding: 36px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid #e5e7eb;
  border-radius: 18px;
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.12);
}

.brand {
  margin-bottom: 28px;
  text-align: center;
}

.brand-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  margin: 0 auto 14px;
  color: #ffffff;
  font-size: 22px;
  font-weight: 800;
  background: #2563eb;
  border-radius: 14px;
}

.brand h1 {
  margin: 0;
  color: #111827;
  font-size: 30px;
}

.brand p {
  margin: 9px 0 0;
  color: #6b7280;
  font-size: 14px;
  line-height: 1.6;
}

.login-form {
  display: grid;
  gap: 18px;
}

.field {
  display: grid;
  gap: 7px;
}

.field label {
  color: #374151;
  font-size: 14px;
  font-weight: 600;
}

.field input {
  width: 100%;
  height: 46px;
  padding: 0 13px;
  color: #111827;
  background: #ffffff;
  border: 1px solid #d1d5db;
  border-radius: 9px;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.field input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.login-form button {
  height: 46px;
  color: #ffffff;
  font-weight: 700;
  background: #2563eb;
  border: 0;
  border-radius: 9px;
  transition: background 0.2s, transform 0.2s;
}

.login-form button:hover:not(:disabled) {
  background: #1d4ed8;
  transform: translateY(-1px);
}

.login-form button:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.error-message {
  margin: 0;
  padding: 10px 12px;
  color: #b91c1c;
  font-size: 13px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
}
</style>