<script setup>
import { computed, onBeforeUnmount, ref } from "vue";
import { uploadResume, validateResumeFile, waitForUpload } from "../services/resumeUpload";
import { PollTimeoutError } from "../utils/pollWithBackoff";

const emit = defineEmits(["uploaded"]);

const resumeName = ref("");
const file = ref(null);
const fileInput = ref(null);
const validationError = ref("");

// idle | requesting | uploading | processing | completed | failed | timeout
const phase = ref("idle");
const task = ref(null);
const errorMessage = ref("");

let controller = null;
// 同一个文件重试时复用同一个幂等键；换文件或改名后生成新的。
let idempotencyKey = null;

const busy = computed(() => ["requesting", "uploading", "processing"].includes(phase.value));

const STATUS_LABELS = {
  UPLOADING: "Waiting for the file",
  PENDING: "Queued for processing",
  PROCESSING: "Parsing your resume"
};

const phaseLabel = computed(() => {
  if (phase.value === "requesting") return "Preparing upload…";
  if (phase.value === "uploading") return "Uploading file…";
  if (phase.value === "processing") return STATUS_LABELS[task.value?.status] ?? "Processing…";
  return "";
});

function onFileChange(event) {
  file.value = event.target.files?.[0] ?? null;
  idempotencyKey = null;
  validationError.value = file.value ? validateResumeFile(file.value) : "";
  if (file.value && !resumeName.value.trim()) resumeName.value = file.value.name.replace(/\.(pdf|docx)$/i, "");
}

function startController() {
  controller?.abort();
  controller = new AbortController();
  return controller.signal;
}

async function handleUpload() {
  validationError.value = validateResumeFile(file.value);
  if (!resumeName.value.trim()) validationError.value ||= "Enter a name for this resume.";
  if (validationError.value) return;

  idempotencyKey ??= crypto.randomUUID();
  errorMessage.value = "";
  task.value = null;

  try {
    const result = await uploadResume({
      file: file.value,
      resumeName: resumeName.value.trim(),
      idempotencyKey,
      onPhase: value => (phase.value = value),
      onStatus: value => (task.value = value),
      signal: startController()
    });
    finish(result);
  } catch (error) {
    handleError(error);
  }
}

/** 轮询超时后用户手动继续等待。 */
async function handleCheckAgain() {
  if (!task.value?.uploadId) return;
  phase.value = "processing";
  errorMessage.value = "";
  try {
    finish(await waitForUpload(task.value.uploadId, { onStatus: value => (task.value = value), signal: startController() }));
  } catch (error) {
    handleError(error);
  }
}

function finish(result) {
  task.value = result;
  if (result.status === "COMPLETED") {
    phase.value = "completed";
    idempotencyKey = null;
    emit("uploaded", result.resumeId);
    return;
  }
  // DEAD：重试耗尽；EXPIRED：上传会话过期。都必须明确告诉用户失败了，不能一直转圈。
  phase.value = "failed";
  idempotencyKey = null;
  errorMessage.value = result.status === "EXPIRED"
    ? "The upload session expired. Please upload the file again."
    : `We couldn't process this resume${result.lastErrorMessage ? `: ${result.lastErrorMessage}` : "."}`;
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
  task.value = null;
  errorMessage.value = "";
  file.value = null;
  resumeName.value = "";
  idempotencyKey = null;
  if (fileInput.value) fileInput.value.value = "";
}

onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <article class="panel">
    <div class="panel-heading">
      <h2>Upload Resume</h2>
      <p>PDF or DOCX, up to 10 MB. The file goes straight to storage, then we parse it in the background.</p>
    </div>

    <form class="form" @submit.prevent="handleUpload">
      <label for="resumeName">Resume name</label>
      <input id="resumeName" v-model="resumeName" type="text" maxlength="256" placeholder="e.g. Backend Engineer" :disabled="busy">

      <label for="resumeFile">File</label>
      <input id="resumeFile" ref="fileInput" type="file" accept=".pdf,.docx" :disabled="busy" @change="onFileChange">

      <p v-if="validationError" class="field-error">{{ validationError }}</p>

      <button class="primary-button" type="submit" :disabled="busy || !file">
        {{ busy ? "Uploading…" : "Upload" }}
      </button>
    </form>

    <div v-if="busy" class="state-box state-loading">
      <span class="spinner" aria-hidden="true"></span>
      <div>
        <strong>{{ phaseLabel }}</strong>
        <p v-if="task?.attemptCount > 1">Attempt {{ task.attemptCount }} — retrying after a temporary problem.</p>
      </div>
    </div>

    <div v-else-if="phase === 'completed'" class="state-box state-success">
      <div>
        <strong>Resume uploaded and parsed.</strong>
        <p>Version {{ task.versionNumber }} is now in your list.</p>
      </div>
      <button class="secondary-button" type="button" @click="reset">Upload another</button>
    </div>

    <div v-else-if="phase === 'failed'" class="state-box state-error">
      <div>
        <strong>Upload failed.</strong>
        <p>{{ errorMessage }}</p>
      </div>
      <button class="secondary-button" type="button" @click="handleUpload">Try again</button>
    </div>

    <div v-else-if="phase === 'timeout'" class="state-box state-warning">
      <div>
        <strong>Still processing.</strong>
        <p>This is taking longer than usual. It will appear in your list when it's done.</p>
      </div>
      <button class="secondary-button" type="button" @click="handleCheckAgain">Check again</button>
    </div>
  </article>
</template>
