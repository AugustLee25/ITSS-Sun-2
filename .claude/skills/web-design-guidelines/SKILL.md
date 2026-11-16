---
name: web-design-guidelines
description: Review UI code for Web Interface Guidelines compliance (accessibility, focus, forms, touch targets, motion, i18n). Use when asked to "review my UI", "check accessibility", "audit design" or "review UX" of FE code.
metadata:
  author: vercel
  version: "1.0.0"
  argument-hint: <file-or-pattern>
---

# Web Interface Guidelines (pinned local copy)

Review the given files (or ask for a file/glob if none given) against the rules in [`guidelines.md`](guidelines.md) and output findings in the terse `file:line - issue` format described at its end.

This is a **pinned local snapshot** of `vercel-labs/web-interface-guidelines` `command.md` (see `../SOURCES.md`). The upstream skill fetches the rules from GitHub at runtime; that fetch was removed on purpose so the project never loads unreviewed remote instructions. Do not fetch the URL; update the snapshot manually if needed.

## HANARE adaptations (override the guidelines where they differ)
- UI language is Vietnamese (source of truth: `FE/src/i18n/vi.ts`). Ignore the "Title Case" copy rule; use Vietnamese sentence case. Hard-coded UI strings outside i18n are a finding.
- Pure client SPA (Vite): ignore the "Hydration Safety" section.
- Spec targets: touch targets >= 44x44 px, WCAG 2.2 AA, status must never rely on colour alone (always a text label/icon), visible `:focus-visible`, `prefers-reduced-motion` respected.
- Dates/numbers via `Intl.*` with the group timezone; scores shown with the precision defined in the spec.
