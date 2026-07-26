import { createRouter, createWebHistory } from "vue-router";
import Login from "../views/Login.vue";
import Dashboard from "../views/Dashboard.vue";

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
  const hasToken = Boolean(localStorage.getItem("accessToken"));

  if (to.meta.requiresAuth && !hasToken) return { name: "login", query: { redirect: to.fullPath } };
  if (to.name === "login" && hasToken) return { name: "dashboard" };
});

export default router;