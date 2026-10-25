<script setup>
import { onMounted, reactive, ref } from "vue";
import { createJob, listJobs } from "../api/job";

const PAGE_SIZE = 10;

const props = defineProps({
  selectedJobId: { type: Number, default: null }
});
const emit = defineEmits(["select"]);

const form = reactive({ companyName: "", jobTitle: "", location: "", description: "" });
const creating = ref(false);
const formError = ref("");

const jobs = ref([]);
const nextCursor = ref(null);
const listLoading = ref(false);
const listError = ref("");

async function loadJobs({ append = false } = {}) {
  listLoading.value = true;
  listError.value = "";
  try {
    const page = await listJobs(append ? nextCursor.value ?? undefined : undefined, PAGE_SIZE);
    jobs.value = append ? [...jobs.value, ...(page.items ?? [])] : page.items ?? [];
    nextCursor.value = page.hasNext ? page.nextCursor : null;
    if (!append && !props.selectedJobId && jobs.value.length) emit("select", jobs.value[0]);
  } catch (error) {
    listError.value = error.message;
  } finally {
    listLoading.value = false;
  }
}

async function handleCreate() {
  formError.value = "";
  if (!form.companyName.trim() || !form.jobTitle.trim() || !form.description.trim()) {
    formError.value = "Company, title and description are required.";
    return;
  }
  creating.value = true;
  try {
    const job = await createJob({
      companyName: form.companyName.trim(),
      jobTitle: form.jobTitle.trim(),
      location: form.location.trim() || undefined,
      description: form.description.trim()
    });
    Object.assign(form, { companyName: "", jobTitle: "", location: "", description: "" });
    await loadJobs();
    emit("select", job);
  } catch (error) {
    // 网络 / 5xx 已由全局提示处理；这里展示校验失败等本表单的错误。
    if (!error.isGeneric) formError.value = error.detail || error.message;
  } finally {
    creating.value = false;
  }
}

onMounted(() => loadJobs());
</script>

<template>
  <article class="panel">
    <div class="panel-heading">
      <h2>Job Descriptions</h2>
      <p>Paste a job description, then match it against one of your resumes.</p>
    </div>

    <form class="form" @submit.prevent="handleCreate">
      <div class="form-row">
        <div>
          <label for="companyName">Company</label>
          <input id="companyName" v-model="form.companyName" maxlength="256" placeholder="e.g. Google" :disabled="creating">
        </div>
        <div>
          <label for="jobTitle">Title</label>
          <input id="jobTitle" v-model="form.jobTitle" maxlength="256" placeholder="e.g. Backend Engineer" :disabled="creating">
        </div>
      </div>
      <label for="jobLocation">Location (optional)</label>
      <input id="jobLocation" v-model="form.location" maxlength="256" placeholder="e.g. Remote" :disabled="creating">
      <label for="jobDescription">Description</label>
      <textarea id="jobDescription" v-model="form.description" rows="6" maxlength="20000"
                placeholder="Paste the full job description" :disabled="creating"></textarea>
      <p v-if="formError" class="field-error">{{ formError }}</p>
      <button class="primary-button" type="submit" :disabled="creating">{{ creating ? "Saving…" : "Save job" }}</button>
    </form>

    <h3 class="list-title">Saved jobs</h3>

    <div v-if="listLoading && !jobs.length" class="state-box state-loading"><span>Loading jobs…</span></div>

    <div v-else-if="listError && !jobs.length" class="state-box state-error">
      <div>
        <strong>Couldn't load your jobs.</strong>
        <p>{{ listError }}</p>
      </div>
      <button class="secondary-button" type="button" @click="loadJobs()">Retry</button>
    </div>

    <div v-else-if="!jobs.length" class="state-box state-empty"><span>No jobs yet. Save one above.</span></div>

    <template v-else>
      <ul class="job-list">
        <li v-for="job in jobs" :key="job.id">
          <button type="button" class="job-item" :class="{ active: job.id === selectedJobId }" @click="emit('select', job)">
            <strong>{{ job.jobTitle }}</strong>
            <span>{{ job.companyName }}<template v-if="job.location"> · {{ job.location }}</template></span>
          </button>
        </li>
      </ul>
      <p v-if="listError" class="field-error">{{ listError }}</p>
      <button v-if="nextCursor" class="secondary-button load-more" type="button" :disabled="listLoading"
              @click="loadJobs({ append: true })">
        {{ listLoading ? "Loading…" : "Load more" }}
      </button>
    </template>
  </article>
</template>

<style scoped>
.form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.form-row > div {
  display: grid;
  gap: 10px;
}

.form textarea {
  width: 100%;
  padding: 11px 12px;
  color: #111827;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  outline: none;
  resize: vertical;
  font-family: inherit;
  line-height: 1.5;
}

.form textarea:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.field-error {
  margin: 0;
  color: #b91c1c;
  font-size: 13px;
}

.list-title {
  margin: 24px 0 0;
  font-size: 15px;
}

.job-list {
  display: grid;
  gap: 8px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.job-item {
  width: 100%;
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  text-align: left;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
}

.job-item:hover {
  border-color: #93c5fd;
}

.job-item.active {
  background: #eff6ff;
  border-color: #2563eb;
}

.job-item span {
  color: #6b7280;
  font-size: 13px;
}

.load-more {
  width: 100%;
  margin-top: 12px;
}

@media (max-width: 640px) {
  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
