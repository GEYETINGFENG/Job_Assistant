import js from "@eslint/js";
import pluginVue from "eslint-plugin-vue";
import tseslint from "typescript-eslint";
import globals from "globals";

/**
 * 只检查可能是 bug 或无用代码的问题（未使用的变量、未定义的标识符、Vue 模板错误等），
 * 不检查属性换行、缩进之类的纯格式规则：所以 Vue 用 essential 而不是 recommended。
 */
export default [
  // schema.d.ts 由 openapi-typescript 从后端契约生成，不手改，也不检查。
  { ignores: ["dist/**", "node_modules/**", "src/api/schema.d.ts"] },

  js.configs.recommended,
  ...pluginVue.configs["flat/essential"],
  ...tseslint.configs.recommended.map(config => ({ ...config, files: ["**/*.ts"] })),

  {
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: { ...globals.browser }
    }
  },
  {
    // 构建配置在 Node 里运行。
    files: ["vite.config.js", "eslint.config.js"],
    languageOptions: { globals: { ...globals.node } }
  },
  {
    rules: {
      // Login、Dashboard 是路由级页面，单词命名不会和 HTML 元素冲突。
      "vue/multi-word-component-names": "off"
    }
  }
];
