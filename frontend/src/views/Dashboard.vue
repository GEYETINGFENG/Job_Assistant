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
  <div class="dashboard-page">
    <header class="nav">
      <div class="nav-inner">
        <span class="wordmark">JobAssistant</span>
        <div class="nav-user">
          <span class="nav-name">{{ displayName }}<span class="nav-role"> · {{ displayRole }}</span></span>
          <button class="nav-link" type="button" :disabled="loggingOut" @click="handleLogout">Sign out</button>
        </div>
      </div>
    </header>

    <main class="content">
      <section class="hero">
        <h1>Hello, {{ displayName }}.</h1>
        <p>Keep your resumes current and see how each one measures up to the jobs you want.</p>
      </section>

      <section class="section" aria-labelledby="resumes-title">
        <h2 id="resumes-title" class="section-title">Resumes</h2>
        <div class="content-grid">
          <ResumeUploadPanel @uploaded="id => (uploadedResumeId = id)" />
          <ResumeListPanel :highlight-id="uploadedResumeId" />
        </div>
      </section>

      <section class="section" aria-labelledby="jobs-title">
        <h2 id="jobs-title" class="section-title">Jobs &amp; matching</h2>
        <div class="content-grid">
          <JobPanel :selected-job-id="selectedJob?.id ?? null" @select="job => (selectedJob = job)" />
          <MatchPanel :job="selectedJob" />
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.dashboard-page {
  min-height: 100vh;
  background: var(--bg);
}

/* 半透明毛玻璃导航栏，滚动时停在顶部。 */
.nav {
  position: sticky;
  top: 0;
  z-index: 100;
  background: var(--nav-bg);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  box-shadow: 0 1px 0 var(--separator);
}

.nav-inner {
  max-width: 1120px;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 0 auto;
  padding: 0 24px;
}

.wordmark {
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.nav-user {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 20px;
  font-size: 13px;
}

.nav-name {
  overflow: hidden;
  color: var(--text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-role {
  color: var(--text-secondary);
}

.nav-link {
  flex: none;
  padding: 0;
  color: var(--accent);
  font-size: 13px;
  background: none;
  border: 0;
}

.nav-link:hover:not(:disabled) {
  text-decoration: underline;
}

.nav-link:disabled {
  opacity: 0.4;
}

.content {
  max-width: 1120px;
  margin: 0 auto;
  padding: 0 24px 80px;
}

.hero {
  padding: 64px 0 40px;
}

.hero h1 {
  margin: 0;
  font-size: 48px;
  font-weight: 600;
  line-height: 1.08;
  letter-spacing: -0.03em;
}

.hero p {
  max-width: 560px;
  margin: 14px 0 0;
  color: var(--text-secondary);
  font-size: 21px;
  line-height: 1.38;
  letter-spacing: -0.01em;
}

.section + .section {
  margin-top: 56px;
}

.section-title {
  margin: 0 0 18px;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.content-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 20px;
}

@media (max-width: 900px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .nav-inner, .content {
    padding-left: 16px;
    padding-right: 16px;
  }

  .nav-role {
    display: none;
  }

  .hero {
    padding: 40px 0 28px;
  }

  .hero h1 {
    font-size: 34px;
  }

  .hero p {
    font-size: 17px;
  }

  .section + .section {
    margin-top: 40px;
  }

  .section-title {
    font-size: 24px;
  }
}
</style>
