<script setup>
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { userLogout } from "../api/user";
import { tokenStorage } from "../auth/tokenStorage";
import { useAuthStore } from "../stores/auth";
import JobPanel from "../components/JobPanel.vue";
import MatchPanel from "../components/MatchPanel.vue";
import ResumeListPanel from "../components/ResumeListPanel.vue";
import ResumeUploadPanel from "../components/ResumeUploadPanel.vue";

const router = useRouter();
const authStore = useAuthStore();
const uploadedResumeId = ref(null);
const selectedJob = ref(null);
const loggingOut = ref(false);

const displayName = computed(() => authStore.user?.username || authStore.user?.userAccount || "Authenticated User");
const displayRole = computed(() => (authStore.user?.userRole === 1 ? "Admin" : "User"));

async function handleLogout() {
  loggingOut.value = true;
  try {
    // 服务端撤销整个 token family；即使失败也清理本地会话。
    const refreshToken = tokenStorage.getRefreshToken();
    if (refreshToken) await userLogout(refreshToken);
  } catch (error) {
    console.warn("Server logout request failed", error);
  } finally {
    authStore.clearAuth();
    loggingOut.value = false;
    await router.replace("/login");
  }
}
</script>

<template>
  <main class="dashboard-page">
    <header class="topbar">
      <div>
        <h1>JobAssistant</h1>
        <p>Resume and career management dashboard</p>
      </div>

      <div class="user-actions">
        <div class="user-summary">
          <strong>{{ displayName }}</strong>
          <span>{{ displayRole }}</span>
        </div>
        <button class="logout-button" :disabled="loggingOut" @click="handleLogout">Logout</button>
      </div>
    </header>

    <section class="content-grid">
      <ResumeUploadPanel @uploaded="id => (uploadedResumeId = id)" />
      <ResumeListPanel :highlight-id="uploadedResumeId" />
    </section>

    <section class="content-grid">
      <JobPanel :selected-job-id="selectedJob?.id ?? null" @select="job => (selectedJob = job)" />
      <MatchPanel :job="selectedJob" />
    </section>
  </main>
</template>

<style scoped>
.dashboard-page {
  min-height: 100vh;
  padding: 28px;
  background: #f5f7fb;
}

.topbar {
  max-width: 1280px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin: 0 auto;
  padding: 22px 24px;
  background: #111827;
  border-radius: 16px;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.15);
}

.topbar h1 {
  margin: 0;
  color: #ffffff;
  font-size: 27px;
}

.topbar p {
  margin: 6px 0 0;
  color: #9ca3af;
  font-size: 14px;
}

.user-actions {
  display: flex;
  align-items: center;
  gap: 18px;
}

.user-summary {
  display: grid;
  gap: 4px;
  text-align: right;
}

.user-summary strong {
  color: #ffffff;
}

.user-summary span {
  color: #9ca3af;
  font-size: 13px;
}

.logout-button {
  height: 40px;
  padding: 0 17px;
  color: #ffffff;
  background: transparent;
  border: 1px solid #4b5563;
  border-radius: 8px;
}

.logout-button:hover:not(:disabled) {
  background: #1f2937;
}

.content-grid {
  max-width: 1280px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 20px;
  margin: 20px auto 0;
}

@media (max-width: 900px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .dashboard-page {
    padding: 16px;
  }

  .topbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .user-actions {
    width: 100%;
    justify-content: space-between;
  }

  .user-summary {
    text-align: left;
  }
}
</style>
