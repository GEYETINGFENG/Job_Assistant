import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { setGlobalErrorHandler } from './api/request'
import { useNotificationStore } from './stores/notifications'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

// 网络 / 超时 / 5xx 统一弹出全局提示，页面只处理自己特有的错误。
const notifications = useNotificationStore(pinia)
setGlobalErrorHandler(error => notifications.push(error.message))

app.mount('#app')
