import { defineStore } from "pinia";

let nextId = 1;

/**
 * 全局提示。网络 / 超时 / 5xx 等通用错误由 request 层推到这里，页面不需要重复处理。
 */
export const useNotificationStore = defineStore("notifications", {
  state: () => ({
    items: []
  }),
  actions: {
    push(message, { type = "error", duration = 5000 } = {}) {
      // 同一条提示已经在显示时不重复弹出（例如断网时多个请求同时失败）。
      if (this.items.some(item => item.message === message)) return;
      const id = nextId++;
      this.items.push({ id, message, type });
      if (duration > 0) setTimeout(() => this.dismiss(id), duration);
    },
    dismiss(id) {
      this.items = this.items.filter(item => item.id !== id);
    }
  }
});
