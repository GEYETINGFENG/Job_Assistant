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
  top: 20px;
  right: 20px;
  z-index: 1000;
  display: grid;
  gap: 10px;
  width: min(380px, calc(100vw - 40px));
}

.toast {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  font-size: 14px;
  border-radius: 10px;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.15);
}

.toast-error {
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
}

.toast-info {
  color: #1e40af;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}

.toast button {
  padding: 0;
  color: inherit;
  font-size: 18px;
  line-height: 1;
  background: none;
  border: 0;
}
</style>
