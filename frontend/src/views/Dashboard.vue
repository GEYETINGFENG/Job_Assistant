<script setup>
import { computed, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { createResume, getResumeById } from "../api/resume";
import { userLogout } from "../api/user";
import { useAuthStore } from "../stores/auth";

const router = useRouter();
const authStore = useAuthStore();
const creating = ref(false);
const querying = ref(false);
const successMessage = ref("");
const errorMessage = ref("");
const queryResumeId = ref("");
const resumeResult = ref(null);

const resumeForm = reactive({
  resumeName: "Backend Engineer Resume",
  fileUrl: "https://example.com/resumes/backend-engineer.pdf",
  parsedJsonText: `{
  "name": "Test User",
  "skills": ["Java", "Spring Boot", "PostgreSQL"],
  "education": [
    {
      "school": "Example University",
      "major": "Software Engineering"
    }
  ]
}`
});

const displayName = computed(() => authStore.user?.username || authStore.user?.userAccount || "Authenticated User");
const displayRole = computed(() => authStore.user?.userRole ?? authStore.user?.role ?? "Unknown");

function clearMessages() {
  successMessage.value = "";
  errorMessage.value = "";
}

async function handleCreateResume() {
  clearMessages();
  creating.value = true;

  try {
    const parsedJson = JSON.parse(resumeForm.parsedJsonText);
    const response = await createResume({
      resumeName: resumeForm.resumeName.trim(),
      fileUrl: resumeForm.fileUrl.trim(),
      parsedJson
    });

    if (response.data.code !== 0) {
      errorMessage.value = response.data.message || "Failed to create resume";
      return;
    }

    queryResumeId.value = String(response.data.data);
    successMessage.value = `Resume created successfully. ID: ${response.data.data}`;
  } catch (error) {
    if (error instanceof SyntaxError) errorMessage.value = "Parsed JSON must be valid JSON";
    else errorMessage.value = error.response?.data?.message || "Failed to create resume";
  } finally {
    creating.value = false;
  }
}

async function handleGetResume() {
  clearMessages();
  resumeResult.value = null;

  if (!queryResumeId.value || Number(queryResumeId.value) <= 0) {
    errorMessage.value = "Enter a valid resume ID";
    return;
  }

  querying.value = true;

  try {
    const response = await getResumeById(queryResumeId.value);

    if (response.data.code !== 0) {
      errorMessage.value = response.data.message || "Failed to query resume";
      return;
    }

    resumeResult.value = response.data.data;
    successMessage.value = "Resume loaded successfully";
  } catch (error) {
    const status = error.response?.status;

    if (status === 404) errorMessage.value = "Resume does not exist or does not belong to the current user";
    else if (status === 403) errorMessage.value = "You do not have permission to access this resume";
    else errorMessage.value = error.response?.data?.message || "Failed to query resume";
  } finally {
    querying.value = false;
  }
}

async function handleLogout() {
  try {
    await userLogout();
  } catch (error) {
    console.warn("Server logout request failed", error);
  } finally {
    authStore.clearAuth();
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
          <span>Role: {{ displayRole }}</span>
        </div>
        <button class="logout-button" @click="handleLogout">Logout</button>
      </div>
    </header>

    <div class="message-area">
      <p v-if="successMessage" class="success-message">{{ successMessage }}</p>
      <p v-if="errorMessage" class="error-message">{{ errorMessage }}</p>
    </div>

    <section class="content-grid">
      <article class="panel">
        <div class="panel-heading">
          <h2>Create Resume</h2>
          <p>Calls POST /api/resumes using the current JWT.</p>
        </div>

        <form class="form" @submit.prevent="handleCreateResume">
          <label for="resumeName">Resume Name</label>
          <input id="resumeName" v-model="resumeForm.resumeName" type="text" placeholder="Resume name">

          <label for="fileUrl">File URL</label>
          <input id="fileUrl" v-model="resumeForm.fileUrl" type="url" placeholder="https://example.com/resume.pdf">

          <label for="parsedJson">Parsed JSON</label>
          <textarea id="parsedJson" v-model="resumeForm.parsedJsonText" rows="12" spellcheck="false"></textarea>

          <button class="primary-button" type="submit" :disabled="creating">{{ creating ? "Creating..." : "Create Resume" }}</button>
        </form>
      </article>

      <article class="panel">
        <div class="panel-heading">
          <h2>Query Resume</h2>
          <p>Calls GET /api/resumes/{id} and verifies ownership.</p>
        </div>

        <form class="query-form" @submit.prevent="handleGetResume">
          <input v-model="queryResumeId" type="number" min="1" placeholder="Resume ID">
          <button class="primary-button" type="submit" :disabled="querying">{{ querying ? "Loading..." : "Get Resume" }}</button>
        </form>

        <div v-if="resumeResult" class="result-card">
          <div class="result-heading">
            <h3>{{ resumeResult.resumeName || "Resume" }}</h3>
            <span>ID: {{ resumeResult.id }}</span>
          </div>

          <dl>
            <div>
              <dt>File URL</dt>
              <dd>{{ resumeResult.fileUrl || "Not provided" }}</dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>{{ resumeResult.status ?? "Not provided" }}</dd>
            </div>
          </dl>

          <h4>Parsed JSON</h4>
          <pre>{{ JSON.stringify(resumeResult.parsedJson, null, 2) }}</pre>
        </div>

        <div v-else class="empty-result">Enter a resume ID to load its details.</div>
      </article>
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

.logout-button:hover {
  background: #1f2937;
}

.message-area {
  max-width: 1280px;
  min-height: 56px;
  margin: 16px auto 0;
}

.success-message, .error-message {
  margin: 0;
  padding: 12px 14px;
  border-radius: 9px;
  font-size: 14px;
}

.success-message {
  color: #166534;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
}

.error-message {
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
}

.content-grid {
  max-width: 1280px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  margin: 0 auto;
}

.panel {
  padding: 24px;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}

.panel-heading {
  margin-bottom: 20px;
}

.panel-heading h2 {
  margin: 0;
  color: #111827;
  font-size: 20px;
}

.panel-heading p {
  margin: 6px 0 0;
  color: #6b7280;
  font-size: 13px;
}

.form {
  display: grid;
  gap: 10px;
}

.form label {
  color: #374151;
  font-size: 13px;
  font-weight: 700;
}

.form input, .form textarea, .query-form input {
  width: 100%;
  padding: 11px 12px;
  color: #111827;
  background: #ffffff;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  outline: none;
}

.form textarea {
  resize: vertical;
  font-family: Consolas, Monaco, monospace;
  line-height: 1.5;
}

.form input:focus, .form textarea:focus, .query-form input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.primary-button {
  min-height: 42px;
  padding: 0 16px;
  color: #ffffff;
  font-weight: 700;
  background: #2563eb;
  border: 0;
  border-radius: 8px;
}

.primary-button:hover:not(:disabled) {
  background: #1d4ed8;
}

.primary-button:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.query-form {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
}

.result-card {
  margin-top: 20px;
  padding: 18px;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
}

.result-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.result-heading h3 {
  margin: 0;
}

.result-heading span {
  color: #6b7280;
  font-size: 13px;
}

.result-card dl {
  display: grid;
  gap: 10px;
  margin: 18px 0;
}

.result-card dl div {
  display: grid;
  grid-template-columns: 90px 1fr;
  gap: 10px;
}

.result-card dt {
  color: #6b7280;
  font-size: 13px;
}

.result-card dd {
  margin: 0;
  color: #111827;
  overflow-wrap: anywhere;
}

.result-card h4 {
  margin: 0 0 8px;
}

.result-card pre {
  max-height: 300px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  color: #e5e7eb;
  background: #111827;
  border-radius: 8px;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.55;
}

.empty-result {
  margin-top: 20px;
  padding: 36px 18px;
  color: #9ca3af;
  text-align: center;
  background: #f9fafb;
  border: 1px dashed #d1d5db;
  border-radius: 10px;
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

  .query-form {
    grid-template-columns: 1fr;
  }
}
</style>