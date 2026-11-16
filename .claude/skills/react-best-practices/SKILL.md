---
name: react-best-practices
description: React performance rules from Vercel Engineering, trimmed for a client-side React 19 + Vite SPA (no Next.js/RSC). Use when writing, reviewing or refactoring React components, effects, state, lists and bundle size.
license: MIT
metadata:
  author: vercel
  version: "1.0.0"
  trimmed-for: HANARE FE (React 19 + TypeScript + Vite + TanStack Query)
---

# React Best Practices (trimmed for Vite SPA)

Subset of Vercel's `react-best-practices` (see `../SOURCES.md`). Server-side/Next.js/SWR/RSC rules were removed because HANARE FE is a pure client SPA. Data fetching uses TanStack Query (not SWR).

Adaptations when a rule mentions Next.js:
- `bundle-dynamic-imports`: use `React.lazy` + `<Suspense>` (route-level code splitting in React Router) instead of `next/dynamic`.
- `bundle-barrel-imports`: Vite has no automatic barrel optimisation; import directly from the module path (the "non-Next.js" variant).

## Rule categories (read the rule file only when relevant)

### Async (CRITICAL) — `rules/async-*`
- `async-cheap-condition-before-await` - Check cheap sync conditions before awaiting
- `async-defer-await` - Move await into the branch that uses it
- `async-parallel` - `Promise.all()` for independent operations

### Bundle size (CRITICAL) — `rules/bundle-*`
- `bundle-barrel-imports` - Import directly, avoid barrel files
- `bundle-dynamic-imports` - Lazy-load heavy components (see adaptation above)
- `bundle-conditional` - Load modules only when the feature is activated
- `bundle-preload` - Preload on hover/focus for perceived speed

### Client (MEDIUM-HIGH) — `rules/client-*`
- `client-passive-event-listeners` - Passive listeners for scroll/touch
- `client-localstorage-schema` - Version and minimise localStorage data (e.g. the JWT/session key)

### Re-render (MEDIUM) — `rules/rerender-*`
- `rerender-defer-reads` - Don't subscribe to state only used in callbacks
- `rerender-memo` - Extract expensive work into memoized components
- `rerender-memo-with-default-value` - Hoist default non-primitive props
- `rerender-dependencies` - Primitive dependencies in effects
- `rerender-derived-state` - Subscribe to derived booleans, not raw values
- `rerender-derived-state-no-effect` - Derive state during render, not in effects
- `rerender-functional-setstate` - Functional setState for stable callbacks
- `rerender-lazy-state-init` - Pass a function to `useState` for expensive init
- `rerender-simple-expression-in-memo` - Don't memo simple primitives
- `rerender-split-combined-hooks` - Split hooks with independent dependencies
- `rerender-move-effect-to-event` - Put interaction logic in event handlers
- `rerender-transitions` - `startTransition` for non-urgent updates
- `rerender-use-deferred-value` - Keep inputs responsive during expensive renders
- `rerender-use-ref-transient-values` - Refs for transient frequent values
- `rerender-no-inline-components` - Don't define components inside components

### Rendering (MEDIUM) — `rules/rendering-*`
- `rendering-activity` - `Activity` for show/hide
- `rendering-animate-svg-wrapper` - Animate a wrapper, not the SVG element
- `rendering-conditional-render` - Ternary, not `&&`, when the left side can be `0`/`""`
- `rendering-content-visibility` - `content-visibility` for long lists
- `rendering-hoist-jsx` - Extract static JSX outside components
- `rendering-resource-hints` - React DOM preload/preconnect APIs
- `rendering-svg-precision` - Reduce SVG coordinate precision
- `rendering-usetransition-loading` - Prefer `useTransition` for pending state

### JavaScript (LOW-MEDIUM) — `rules/js-*`
`js-batch-dom-css`, `js-cache-function-results`, `js-cache-property-access`, `js-cache-storage`, `js-combine-iterations`, `js-early-exit`, `js-flatmap-filter`, `js-hoist-regexp`, `js-index-maps`, `js-length-check-first`, `js-min-max-loop`, `js-request-idle-callback`, `js-set-map-lookups`, `js-tosorted-immutable`

### Advanced (LOW) — `rules/advanced-*`
`advanced-effect-event-deps`, `advanced-event-handler-refs`, `advanced-init-once`, `advanced-use-latest`

## How to use

Each rule file has: why it matters, an incorrect and a correct example, and extra context. Read `rules/<name>.md` for the rules relevant to the code at hand; do not load all of them.
