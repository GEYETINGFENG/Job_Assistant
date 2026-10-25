<script setup>
import { useNotificationStore } from "../stores/notifications";

const notifications = useNotificationStore();
</script>

<template>
  <div class="toast-stack" role="status" aria-live="polite">
    <div v-for="item in notifications.items" :key="item.id" class="toast" :class="`toast-${item.type}`">
      <span>{{ item.message }}</span>
      <button type="button" aria-label="Dismiss" @click="notifications.dismiss(item.id)">×</button>
    </div>
  </div>
</template>

<style scoped>
.toast-stack {
  position: fixed;
  top: 64px;
  right: 20px;
  z-index: 1000;
  display: grid;
  gap: 10px;
  width: min(360px, calc(100vw - 32px));
}

/* 毛玻璃浮层，颜色只用在左侧的圆点上。 */
.toast {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  color: var(--text);
  font-size: 14px;
  line-height: 1.4;
  background: var(--nav-bg);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  border-radius: 14px;
  box-shadow: var(--shadow-float);
}

.toast span::before {
  content: "";
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 10px;
  vertical-align: 1px;
  border-radius: 50%;
}

.toast-error span::before {
  background: var(--danger);
}

.toast-info span::before {
  background: var(--accent);
}

.toast button {
  padding: 0;
  color: var(--text-tertiary);
  font-size: 18px;
  line-height: 1;
  background: none;
  border: 0;
}

.toast button:hover {
  color: var(--text);
}

@media (max-width: 640px) {
  .toast-stack {
    right: 16px;
    top: 60px;
  }
}
</style>
