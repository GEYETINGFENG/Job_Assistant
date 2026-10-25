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

const SECTION_LABELS = {
  SUMMARY: "Summary",
  SKILLS: "Skills",
  EXPERIENCE: "Experience",
  PROJECT: "Project",
  EDUCATION: "Education",
  CERTIFICATE: "Certificates",
  TEXT: "Resume text"
};

// RAG：JD 逐条要求在简历中检索到的证据；旧版本的匹配结果没有这一项。
const requirements = computed(() => match.value?.analysis?.requirements ?? []);
const coveredCount = computed(() => requirements.value.filter(item => item.covered).length);

// 分数圆环：周长 2πr，按分数比例露出描边。
const RING_RADIUS = 52;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;
const ringOffset = computed(() => RING_CIRCUMFERENCE * (1 - Math.min(100, Math.max(0, match.value?.score ?? 0)) / 100));

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

    <!-- 还没有结果时说明能得到什么，而不是留一大块空白。 -->
    <section v-else-if="phase === 'idle' && resumes.length" class="placeholder">
      <div class="score score-empty" aria-hidden="true">
        <svg viewBox="0 0 120 120">
          <circle class="ring-track" cx="60" cy="60" :r="RING_RADIUS" />
        </svg>
        <span class="score-value">–</span>
      </div>
      <h3>See how this resume measures up</h3>
      <p>Pick a resume and run a match. You'll get a score, the skills you match and miss, and evidence from your resume for each requirement.</p>
      <ol class="steps">
        <li><span>1</span>Save or select a job on the left.</li>
        <li><span>2</span>Choose the resume and version to compare.</li>
        <li><span>3</span>Run the match and review each requirement.</li>
      </ol>
    </section>

    <section v-else-if="phase === 'completed' && match" class="result">
      <div class="score-row">
        <div class="score" :class="scoreClass" role="img" :aria-label="`Match score ${match.score} out of 100`">
          <svg viewBox="0 0 120 120" aria-hidden="true">
            <circle class="ring-track" cx="60" cy="60" :r="RING_RADIUS" />
            <circle class="ring-value" cx="60" cy="60" :r="RING_RADIUS"
                    :stroke-dasharray="RING_CIRCUMFERENCE" :stroke-dashoffset="ringOffset" />
          </svg>
          <span class="score-value">{{ match.score }}</span>
        </div>
        <div class="score-text">
          <span class="score-caption">Match score</span>
          <p class="summary">{{ match.analysis?.summary }}</p>
        </div>
      </div>
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

      <template v-if="requirements.length">
        <h4>Requirement coverage <span class="coverage-count">{{ coveredCount }} of {{ requirements.length }} covered</span></h4>
        <ul class="requirements">
          <li v-for="item in requirements" :key="item.requirement" :class="item.covered ? 'covered' : 'uncovered'">
            <div class="requirement-line">
              <span class="requirement-mark" :aria-label="item.covered ? 'Covered' : 'Not found'">{{ item.covered ? "✓" : "" }}</span>
              <span>{{ item.requirement }}</span>
            </div>
            <blockquote v-if="item.covered && item.evidence?.length" class="evidence">
              <span class="evidence-meta">
                {{ SECTION_LABELS[item.evidence[0].section] ?? item.evidence[0].section }} ·
                similarity {{ item.evidence[0].similarity?.toFixed(2) }}
                <template v-if="item.evidence.length > 1"> · +{{ item.evidence.length - 1 }} more</template>
              </span>
              {{ item.evidence[0].excerpt }}
            </blockquote>
            <p v-else class="evidence-missing">No supporting evidence found in this resume.</p>
          </li>
        </ul>
      </template>
    </section>
  </article>
</template>

<style scoped>
.form-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 12px;
}

.form-row > div {
  display: grid;
  gap: 10px;
}

.result {
  margin-top: 28px;
  padding-top: 28px;
  border-top: 1px solid var(--separator);
}

/* ---------- 分数：Apple Fitness 风格的圆环 ---------- */

.score-row {
  display: flex;
  align-items: center;
  gap: 22px;
}

.score {
  --ring: var(--system-red);
  position: relative;
  width: 112px;
  height: 112px;
  flex: none;
  display: grid;
  place-items: center;
}

.score-high {
  --ring: var(--system-green);
}

.score-medium {
  --ring: var(--system-orange);
}

.score svg {
  position: absolute;
  inset: 0;
  transform: rotate(-90deg);
}

.score circle {
  fill: none;
  stroke-width: 10;
}

.ring-track {
  stroke: var(--surface-muted);
}

.ring-value {
  stroke: var(--ring);
  stroke-linecap: round;
  transition: stroke-dashoffset 0.8s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.score-value {
  position: relative;
  font-size: 34px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.score-text {
  min-width: 0;
}

.score-caption {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.summary {
  margin: 6px 0 0;
  color: var(--text);
  font-size: 15px;
  line-height: 1.5;
}

.engine-note {
  margin: 16px 0 0;
  padding: 10px 14px;
  color: var(--warning);
  font-size: 13px;
  line-height: 1.45;
  background: var(--warning-soft);
  border-radius: var(--radius-control);
}

/* ---------- 空状态 ---------- */

.placeholder {
  display: grid;
  justify-items: center;
  margin-top: 28px;
  padding: 36px 8px 8px;
  text-align: center;
  border-top: 1px solid var(--separator);
}

.score-empty .score-value {
  color: var(--text-tertiary);
}

.placeholder h3 {
  margin: 20px 0 0;
  font-size: 21px;
  font-weight: 600;
  letter-spacing: -0.015em;
}

.placeholder p {
  max-width: 420px;
  margin: 8px 0 0;
  color: var(--text-secondary);
  font-size: 15px;
  line-height: 1.5;
}

.steps {
  width: 100%;
  max-width: 420px;
  display: grid;
  gap: 0;
  margin: 28px 0 0;
  padding: 0;
  overflow: hidden;
  text-align: left;
  list-style: none;
  background: var(--surface-muted);
  border-radius: var(--radius-control);
}

.steps li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 16px;
  font-size: 14px;
}

.steps li + li {
  border-top: 1px solid var(--separator);
}

.steps span {
  width: 24px;
  height: 24px;
  flex: none;
  display: grid;
  place-items: center;
  color: var(--accent);
  font-size: 13px;
  font-weight: 600;
  background: var(--accent-soft);
  border-radius: 50%;
}

/* ---------- 技能、差距、建议 ---------- */

.skill-groups {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  margin-top: 8px;
}

h4 {
  margin: 28px 0 10px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.04em;
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
  padding: 5px 12px;
  font-size: 13px;
  font-weight: 500;
  border-radius: var(--radius-pill);
}

.chip-match {
  color: var(--success);
  background: var(--success-soft);
}

.chip-missing {
  color: var(--danger);
  background: var(--danger-soft);
}

.notes {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 18px;
  color: var(--text);
  font-size: 15px;
  line-height: 1.5;
}

.notes li::marker {
  color: var(--text-tertiary);
}

/* ---------- 要求覆盖 ---------- */

.coverage-count {
  margin-left: 6px;
  color: var(--text-tertiary);
  text-transform: none;
  letter-spacing: 0;
}

.requirements {
  margin: 0;
  padding: 0;
  overflow: hidden;
  list-style: none;
  background: var(--surface-muted);
  border-radius: var(--radius-control);
}

.requirements li {
  padding: 14px 16px;
}

.requirements li + li {
  border-top: 1px solid var(--separator);
}

.requirement-line {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  color: var(--text);
  font-size: 15px;
  line-height: 1.45;
}

.requirement-mark {
  width: 20px;
  height: 20px;
  flex: none;
  display: grid;
  place-items: center;
  margin-top: 1px;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  border-radius: 50%;
}

.covered .requirement-mark {
  background: var(--system-green);
}

.uncovered .requirement-mark {
  color: var(--text-tertiary);
  background: transparent;
  box-shadow: inset 0 0 0 1.5px var(--hairline);
}

.evidence {
  margin: 8px 0 0 30px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-line;
}

.evidence-meta {
  display: block;
  margin-bottom: 3px;
  color: var(--text-tertiary);
  font-size: 12px;
}

.evidence-missing {
  margin: 4px 0 0 30px;
  color: var(--text-tertiary);
  font-size: 13px;
}

@media (max-width: 640px) {
  .form-row, .skill-groups {
    grid-template-columns: 1fr;
  }

  .score-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
  }
}
</style>
