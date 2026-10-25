<script setup>
import { onMounted, ref, watch } from "vue";
import { getResumeById, listResumes } from "../api/resume";

const PAGE_SIZE = 10;

const props = defineProps({
  // 上传完成后父组件传入新简历 ID，列表刷新并选中它。
  highlightId: { type: Number, default: null }
});

const items = ref([]);
const page = ref(0);
const hasNext = ref(false);
const listLoading = ref(false);
const listError = ref("");

const selected = ref(null);
const detailLoading = ref(false);
const detailError = ref("");

async function loadList(targetPage = page.value) {
  listLoading.value = true;
  listError.value = "";
  try {
    const result = await listResumes(targetPage, PAGE_SIZE);
    items.value = result.items ?? [];
    hasNext.value = Boolean(result.hasNext);
    page.value = targetPage;
  } catch (error) {
    listError.value = error.message;
  } finally {
    listLoading.value = false;
  }
}

async function selectResume(id) {
  detailLoading.value = true;
  detailError.value = "";
  try {
    selected.value = await getResumeById(id);
  } catch (error) {
    selected.value = null;
    // 404 是本页面特有的语义：简历可能刚被删除，刷新列表。
    if (error.status === 404) loadList();
    detailError.value = error.message;
  } finally {
    detailLoading.value = false;
  }
}

watch(() => props.highlightId, async id => {
  if (!id) return;
  await loadList(0);
  selectResume(id);
});

onMounted(() => loadList(0));

function formatTime(value) {
  return value ? new Date(value).toLocaleString() : "—";
}
</script>

<template>
  <article class="panel">
    <div class="panel-heading panel-heading-row">
      <div>
        <h2>My Resumes</h2>
        <p>Most recently updated first.</p>
      </div>
      <button class="secondary-button" type="button" :disabled="listLoading" @click="loadList()">Refresh</button>
    </div>

    <!-- 三态：loading / error / empty，再加上正常数据。 -->
    <div v-if="listLoading && !items.length" class="state-box state-loading">
      <span>Loading resumes…</span>
    </div>

    <div v-else-if="listError" class="state-box state-error">
      <div>
        <strong>Couldn't load your resumes.</strong>
        <p>{{ listError }}</p>
      </div>
      <button class="secondary-button" type="button" @click="loadList()">Retry</button>
    </div>

    <div v-else-if="!items.length" class="state-box state-empty">
      <span>No resumes yet. Upload one to get started.</span>
    </div>

    <template v-else>
      <ul class="grouped-list" :class="{ dimmed: listLoading }">
        <li v-for="item in items" :key="item.id">
          <button
            type="button"
            class="list-row"
            :class="{ active: selected?.id === item.id }"
            :aria-pressed="selected?.id === item.id"
            @click="selectResume(item.id)"
          >
            <span class="list-row-text">
              <strong>{{ item.resumeName || "Untitled resume" }}</strong>
              <span>Version {{ item.latestVersionNumber }} · {{ formatTime(item.updateTime) }}</span>
            </span>
            <span v-if="selected?.id === item.id" class="list-row-check" aria-hidden="true">✓</span>
          </button>
        </li>
      </ul>

      <div class="pager">
        <button class="pager-button" type="button" :disabled="page === 0 || listLoading" @click="loadList(page - 1)">‹ Previous</button>
        <span>Page {{ page + 1 }}</span>
        <button class="pager-button" type="button" :disabled="!hasNext || listLoading" @click="loadList(page + 1)">Next ›</button>
      </div>
    </template>

    <div v-if="detailLoading" class="state-box state-loading">
      <span>Loading details…</span>
    </div>

    <div v-else-if="detailError" class="state-box state-error">
      <span>{{ detailError }}</span>
    </div>

    <div v-else-if="selected" class="result-card">
      <div class="result-heading">
        <h3>{{ selected.resumeName || "Resume" }}</h3>
        <span>ID {{ selected.id }} · Version {{ selected.latestVersionNumber }}</span>
      </div>
      <h4>Parsed content</h4>
      <pre v-if="selected.parsedJson">{{ JSON.stringify(selected.parsedJson, null, 2) }}</pre>
      <p v-else class="muted">No parsed content for this resume.</p>
    </div>
  </article>
</template>

<style scoped>
.panel-heading-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.grouped-list {
  transition: opacity 0.2s;
}

.grouped-list.dimmed {
  opacity: 0.5;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  color: var(--text-secondary);
  font-size: 13px;
}

.pager-button {
  padding: 6px 2px;
  color: var(--accent);
  font-size: 14px;
  background: none;
  border: 0;
}

.pager-button:hover:not(:disabled) {
  text-decoration: underline;
}

.pager-button:disabled {
  color: var(--text-tertiary);
  cursor: default;
}

.result-card {
  margin-top: 24px;
  padding-top: 22px;
  border-top: 1px solid var(--separator);
}

.result-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
}

.result-heading h3 {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.result-heading span {
  flex: none;
  color: var(--text-secondary);
  font-size: 13px;
}

.result-card h4 {
  margin: 18px 0 8px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.result-card pre {
  max-height: 300px;
  margin: 0;
  padding: 16px;
  overflow: auto;
  color: var(--code-text);
  background: var(--code-bg);
  border-radius: var(--radius-control);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
}
</style>
