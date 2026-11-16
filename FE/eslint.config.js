import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist', 'coverage']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      '@typescript-eslint/consistent-type-imports': 'error',
    },
  },
  {
    // File chứa cả component và hook/hằng số dùng chung: cho phép export hỗn hợp.
    files: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/pages/__kit/**', 'src/**/index.ts', 'src/app/**', 'src/components/**/*Context*.tsx', 'src/components/**/Toast*.tsx'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
])
