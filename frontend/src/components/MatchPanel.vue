<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { listResumes, listResumeVersions } from "../api/resume";
import { runMatch, waitForMatch } from "../services/matchRunner";
import { PollTimeoutError } from "../utils/pollWithBackoff";

// 简历列表接口每页最多 50 条（ResumeServiceImpl.MAX_PAGE_SIZE），下拉框取最近更新的 50 份。
const RESUME_OPTION_LIMIT = 50;

const props = defineProps({
  job: { type: Object, default: null }
});

const resumes = ref([]);
const resumeId = ref(null);
const versions = ref([]);
const versionId = ref(null);
const optionsError = ref("");

// idle | running | completed | failed | timeout
const phase = ref("idle");
const match = ref(null);
const errorMessage = ref("");
let controller = null;

const running = computed(() => phase.value === "running");
const canRun = computed(() => Boolean(props.job?.id && versionId.value) && !running.value);

const STATUS_LABELS = {
  PENDING: "Queued for analysis…",
  PROCESSING: "Analyzing your resume against this job…"
};

const scoreClass = computed(() => {
  const score = match.value?.score ?? 0;
  if (score >= 70) return "score-high";
  if (score >= 40) return "score-medium";
  return "score-low";
});

async function loadResumes() {
  optionsError.value = "";
  try {
    const page = await listResumes(0, RESUME_OPTION_LIMIT);
    resumes.value = page.items ?? [];
    if (!resumeId.value && resumes.value.length) resumeId.value = resumes.value[0].id;
  } catch (error) {
    optionsError.value = error.message;
  }
}

watch(resumeId, async id => {
  versions.value = [];
  versionId.value = null;
  if (!id) return;
  try {
    // 默认选最新版本；投递和匹配都针对具体版本。
    versions.value = [...(await listResumeVersions(id))].sort((a, b) => (b.versionNumber ?? 0) - (a.versionNumber ?? 0));
    versionId.value = versions.value[0]?.id ?? null;
  } catch (error) {
    optionsError.value = error.message;
  }
});

// 换了 JD 就清空上一份结果。
watch(() => props.job?.id, () => reset());

function startController() {
  controller?.abort();
  controller = new AbortController();
  return controller.signal;
}

async function handleRun() {
  phase.value = "running";
  errorMessage.value = "";
  match.value = null;
  try {
    finish(await runMatch({
      resumeVersionId: versionId.value,
      jobId: props.job.id,
      onStatus: value => (match.value = value),
      signal: startController()
    }));
  } catch (error) {
    handleError(error);
  }
}

async function handleCheckAgain() {
  if (!match.value?.id) return;
  phase.value = "running";
  try {
    finish(await waitForMatch(match.value.id, { onStatus: value => (match.value = value), signal: startController() }));
  } catch (error) {
    handleError(error);
  }
}

function finish(result) {
  match.value = result;
  if (result.status === "COMPLETED") {
    phase.value = "completed";
    return;
  }
  phase.value = "failed";
  errorMessage.value = result.lastErrorMessage || "The analysis failed.";
}

function handleError(error) {
  if (error?.name === "AbortError") return;
  if (error instanceof PollTimeoutError) {
    phase.value = "timeout";
    return;
  }
  phase.value = "failed";
  errorMessage.value = error.message;
}

function reset() {
  controller?.abort();
  phase.value = "idle";
  match.value = null;
  errorMessage.value = "";
}

onMounted(loadResumes);
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <article class="panel">
    <div class="panel-heading">
      <h2>Match</h2>
      <p v-if="job">Comparing against <strong>{{ job.jobTitle }}</strong> at {{ job.companyName }}.</p>
      <p v-else>Select or save a job description first.</p>
    </div>

    <div v-if="optionsError" class="state-box state-error">
      <span>{{ optionsError }}</span>
      <button class="secondary-button" type="button" @click="loadResumes">Retry</button>
    </div>

    <div v-else-if="!resumes.length" class="state-box state-empty">
      <span>Upload a resume first to run a match.</span>
    </div>

    <form v-else class="form" @submit.prevent="handleRun">
      <div class="form-row">
        <div>
          <label for="matchResume">Resume</label>
          <select id="matchResume" v-model="resumeId" :disabled="running">
            <option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.resumeName || `Resume ${resume.id}` }}</option>
          </select>
        </div>
        <div>
          <label for="matchVersion">Version</label>
          <select id="matchVersion" v-model="versionId" :disabled="running || !versions.length">
            <option v-for="version in versions" :key="version.id" :value="version.id">v{{ version.versionNumber }}</option>
          </select>
        </div>
      </div>
      <button class="primary-button" type="submit" :disabled="!canRun">{{ running ? "Matching…" : "Run match" }}</button>
    </form>

    <div v-if="running" class="state-box state-loading">
      <span class="spinner" aria-hidden="true"></span>
      <div>
        <strong>{{ STATUS_LABELS[match?.status] ?? "Starting…" }}</strong>
        <p v-if="(match?.attemptCount ?? 0) > 1">Attempt {{ match.attemptCount }} — retrying after a temporary problem.</p>
      </div>
    </div>

    <div v-else-if="phase === 'failed'" class="state-box state-error">
      <div>
        <strong>Match failed.</strong>
        <p>{{ errorMessage }}</p>
      </div>
      <button class="secondary-button" type="button" :disabled="!canRun" @click="handleRun">Try again</button>
    </div>

    <div v-else-if="phase === 'timeout'" class="state-box state-warning">
      <div>
        <strong>Still analyzing.</strong>
        <p>This is taking longer than usual.</p>
      </div>
      <button class="secondary-button" type="button" @click="handleCheckAgain">Check again</button>
    </div>

    <section v-else-if="phase === 'completed' && match" class="result">
      <div class="score" :class="scoreClass">
        <span class="score-value">{{ match.score }}</span>
        <span class="score-label">/ 100</span>
      </div>
      <p class="summary">{{ match.analysis?.summary }}</p>
      <p v-if="match.engine === 'KEYWORD'" class="engine-note">
        Keyword comparison only — AI analysis is turned off, so experience and seniority are not considered.
      </p>

      <div class="skill-groups">
        <div>
          <h4>Matched skills</h4>
          <ul v-if="match.analysis?.matchedSkills?.length" class="chips">
            <li v-for="skill in match.analysis.matchedSkills" :key="skill" class="chip chip-match">{{ skill }}</li>
          </ul>
          <p v-else class="muted">None found.</p>
        </div>
        <div>
          <h4>Missing skills</h4>
          <ul v-if="match.analysis?.missingSkills?.length" class="chips">
            <li v-for="skill in match.analysis.missingSkills" :key="skill" class="chip chip-missing">{{ skill }}</li>
          </ul>
          <p v-else class="muted">None.</p>
        </div>
      </div>

      <template v-if="match.analysis?.gaps?.length">
        <h4>Gaps</h4>
        <ul class="notes">
          <li v-for="gap in match.analysis.gaps" :key="gap">{{ gap }}</li>
        </ul>
      </template>

      <template v-if="match.analysis?.suggestions?.length">
        <h4>Suggestions</h4>
        <ul class="notes">
          <li v-for="suggestion in match.analysis.suggestions" :key="suggestion">{{ suggestion }}</li>
        </ul>
      </template>
    </section>
  </article>
</template>

<style scoped>
.form-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 10px;
}

.form-row > div {
  display: grid;
  gap: 10px;
}

select {
  width: 100%;
  padding: 10px 12px;
  color: #111827;
  background: #ffffff;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font: inherit;
}

.spinner {
  width: 18px;
  height: 18px;
  flex: none;
  border: 2px solid #bfdbfe;
  border-top-color: #2563eb;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.result {
  margin-top: 20px;
}

.score {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.score-value {
  font-size: 44px;
  font-weight: 800;
  line-height: 1;
}

.score-label {
  color: #6b7280;
}

.score-high .score-value {
  color: #15803d;
}

.score-medium .score-value {
  color: #b45309;
}

.score-low .score-value {
  color: #b91c1c;
}

.summary {
  margin: 10px 0 0;
  color: #374151;
  line-height: 1.5;
}

.engine-note {
  margin: 10px 0 0;
  padding: 8px 10px;
  color: #92400e;
  font-size: 13px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 8px;
}

.skill-groups {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 16px;
}

h4 {
  margin: 16px 0 8px;
  font-size: 14px;
}

.skill-groups h4 {
  margin-top: 0;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.chip {
  padding: 4px 10px;
  font-size: 13px;
  border-radius: 999px;
}

.chip-match {
  color: #166534;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
}

.chip-missing {
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
}

.notes {
  margin: 0;
  padding-left: 18px;
  color: #374151;
  line-height: 1.6;
}

.muted {
  margin: 0;
  color: #9ca3af;
  font-size: 13px;
}

@media (max-width: 640px) {
  .form-row, .skill-groups {
    grid-template-columns: 1fr;
  }
}
</style>
