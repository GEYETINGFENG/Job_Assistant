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

      <!-- 宽屏三栏工作台：简历 | 职位 | 匹配结果；每栏独立往下排，不会因为相邻卡片高度不同留出大块空白。 -->
      <div class="workspace">
        <section class="column column-resumes" aria-label="Resumes">
          <ResumeUploadPanel @uploaded="id => (uploadedResumeId = id)" />
          <ResumeListPanel :highlight-id="uploadedResumeId" />
        </section>

        <section class="column column-jobs" aria-label="Jobs">
          <JobPanel :selected-job-id="selectedJob?.id ?? null" @select="job => (selectedJob = job)" />
        </section>

        <section class="column column-match" aria-label="Match">
          <MatchPanel :job="selectedJob" />
        </section>
      </div>
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
  max-width: 1600px;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 0 auto;
  padding: 0 32px;
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
  max-width: 1600px;
  margin: 0 auto;
  padding: 0 32px 64px;
}

.hero {
  padding: 36px 0 24px;
}

.hero h1 {
  margin: 0;
  font-size: 36px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.025em;
}

.hero p {
  margin: 8px 0 0;
  color: var(--text-secondary);
  font-size: 17px;
  line-height: 1.4;
}

/* 默认（窄屏）单栏；中屏两栏，匹配结果接在职位下面；宽屏三栏，匹配结果最宽。 */
.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-areas: "resumes" "jobs" "match";
  align-items: start;
  gap: 20px;
}

.column {
  min-width: 0;
  display: grid;
  gap: 20px;
}

.column-resumes {
  grid-area: resumes;
}

.column-jobs {
  grid-area: jobs;
}

.column-match {
  grid-area: match;
}

@media (min-width: 900px) {
  .workspace {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-areas:
      "resumes jobs"
      "resumes match";
  }
}

@media (min-width: 1280px) {
  .workspace {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1.35fr);
    grid-template-areas: "resumes jobs match";
  }

  /* 匹配结果是主要输出：滚动时停在导航栏下方，内容超过一屏时在卡片内滚动。 */
  .column-match {
    position: sticky;
    top: 72px;
    max-height: calc(100vh - 92px);
    overflow-y: auto;
    border-radius: var(--radius-card);
    scrollbar-width: thin;
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
    padding: 28px 0 20px;
  }

  .hero h1 {
    font-size: 30px;
  }

  .hero p {
    font-size: 16px;
  }
}
</style>
