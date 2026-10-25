import { createRouter, createWebHistory } from "vue-router";
import Login from "../views/Login.vue";
import Dashboard from "../views/Dashboard.vue";
import { tokenStorage } from "../auth/tokenStorage";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      redirect: "/dashboard"
    },
    {
      path: "/login",
      name: "login",
      component: Login
    },
    {
      path: "/dashboard",
      name: "dashboard",
      component: Dashboard,
      meta: {
        requiresAuth: true
      }
    },
    {
      path: "/:pathMatch(.*)*",
      redirect: "/dashboard"
    }
  ]
});

router.beforeEach(to => {
  // access token 过期但 refresh token 还在时，仍然算已登录：第一次请求会静默刷新。
  const hasToken = tokenStorage.hasSession();

  if (to.meta.requiresAuth && !hasToken) return { name: "login", query: { redirect: to.fullPath } };
  if (to.name === "login" && hasToken) return { name: "dashboard" };
});

export default router;