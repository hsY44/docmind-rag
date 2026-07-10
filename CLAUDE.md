# CLAUDE.md
Behavioral guidelines to reduce common LLM coding mistakes.
**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding
Before implementing:
- State assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.

## 2. Simplicity First
Minimum code that solves the problem. Nothing speculative.
- No features beyond what was asked.
- No abstractions or configurability for single-use code.
- No error handling for impossible scenarios.
- If 200 lines could be 50, rewrite it.

## 3. Surgical Changes
Touch only what you must.
- Don't improve adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.
- Remove only imports/variables/functions that YOUR changes made unused.

Every changed line should trace directly to the request.

## 4. Goal-Driven Execution
Define success criteria before starting.
- "Fix the bug" → write a test that reproduces it, then make it pass.
- "Add validation" → write tests for invalid inputs, then make them pass.

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
```

## 5. Failure Escalation
Two failed attempts at the same approach = stop and reassess, not retry.
- Declare: "This approach won't work because [reason]."
- Propose the actual alternative. Not a variation of the same path.

## 6. Ambiguity Resolution
Unclear references require a question, not a guess.
- If "which file", "which class", or "which version" is ambiguous, ask first.
- One question is cheaper than implementing the wrong thing.

## 7. Request Flow Before Routing Code
Before any controller, filter, or redirect logic, write the flow explicitly:
```
Request URL → Servlet (mapping) → forward/redirect → View or next URL
```
`forward` and `sendRedirect` have different URL and path semantics.
Wrong choice here breaks CSS paths and session state.

---
**These guidelines are working if:** fewer unnecessary changes in diffs,
fewer rewrites due to overcomplication, and 2+ failed attempts trigger a
strategy change rather than a third try.
