// Correctness-oriented lint gate. Formatting opinions are deliberately not enforced.
// vue-tsc (npm run type-check) already rejects unused locals and unsafe typing.
import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import pluginVue from 'eslint-plugin-vue';

export default tseslint.config(
  {
    ignores: ['dist/**', 'coverage/**', 'node_modules/**', 'screenshots/**', 'public/**'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
  },
  {
    files: ['src/**/*.{ts,vue}'],
    languageOptions: { globals: { ...globals.browser } },
  },
  {
    files: ['scripts/**/*.{mjs,js}', '*.config.{js,ts}', 'vite.config.ts'],
    // Playwright scripts also contain page.evaluate() callbacks that run in the browser.
    languageOptions: { globals: { ...globals.node, ...globals.browser } },
  },
  {
    rules: {
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      'no-var': 'error',
      'prefer-const': 'error',
    },
  },
  {
    // The TypeScript compiler (vue-tsc) already resolves identifiers in typed files.
    files: ['**/*.vue'],
    rules: { 'no-undef': 'off' },
  },
  {
    files: ['src/**/*.{ts,vue}'],
    ignores: ['src/**/__tests__/**'],
    rules: { 'no-console': ['error', { allow: ['warn', 'error'] }] },
  },
);
