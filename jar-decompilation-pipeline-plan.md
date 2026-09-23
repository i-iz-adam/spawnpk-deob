# Clean Decompilation Pipeline — Project Plan

## Goal

Take an arbitrary obfuscated jar and produce the highest-percentage-compilable,
least-manually-patched Java source tree possible, without a single decompiler
exception halting the whole job.

This plan targets general ProGuard-style obfuscation (renamed identifiers,
inlining, shrinking) — not hostile/commercial obfuscators with string
encryption or flattened control flow, which would need extra stages.

---

## Architecture — 5 stages, each independently testable

### Stage 0 — Bytecode normalization (ASM pass, before any decompiling)

This is the highest-leverage stage, and the one generic decompilers skip.

- Walk every `.class` with ASM's `ClassReader` / `ClassNode`.
- Build a global rename map for anything that's illegal or unwise as a Java
  *source* identifier:
  - Reserved words (`do`, `int`, `class`, `goto`, etc.)
  - Names starting with digits or containing invalid identifier characters
  - Names colliding with a same-named subpackage (the classic
    `rs/a/a.class` vs. `rs/a/a/` package collision)
- Apply the map everywhere at once with `ClassRemapper` + `SimpleRemapper` so
  every call site, field reference, and signature stays consistent — not just
  the declarations.
- Also normalize:
  - Synthetic bridge/access methods that some decompilers choke on
  - Illegal inner-class attribute references
  - Malformed `LocalVariableTable` / `StackMapTable` entries left over from
    obfuscator bugs
- **Output:** a second jar, behaviorally identical, with source-legal names.

### Stage 1 — Multi-decompiler harness with per-class isolation

- Embed 2–3 decompilers as libraries, not CLIs:
  - **Vineflower** (actively maintained Fernflower fork) — primary
  - **CFR** — secondary
  - **Procyon** — tertiary fallback
- Decompile class-by-class in a worker with its own `try/catch` and a hard
  timeout. One pathological method must never kill the batch.
- On exception or timeout, fall through decompiler → decompiler in priority
  order.
- If all three fail: emit a stub class with correct signatures/fields
  (recovered directly via ASM, not the decompiler) plus the method bodies as
  a bytecode comment (ASM `Textifier` dump). This keeps the tree compiling
  and leaves a clearly marked TODO instead of a missing class.
- Log every fallback/failure to a manifest so it's obvious which classes need
  manual attention.

### Stage 2 — Output selection / merge

- Where multiple decompilers succeed on the same class, prefer by a simple
  heuristic: fewest raw-type warnings, no synthetic leakage, closest line
  count to the bytecode's method count.
- Simpler alternative: default to Vineflower everywhere, only pull in CFR's
  version on failure.
- Normalize formatting once (a single `google-java-format` pass) so later
  diffs between versions are meaningful.

### Stage 3 — Resource & build scaffolding

- Copy non-`.class` resources into a matching `src/main/resources` tree.
- Generate a build file (Gradle is easiest to template) with dependencies
  inferred from the jar's own bundled libraries — fingerprint bundled
  third-party jars by hash/class-shape against Maven Central rather than
  deobfuscating them; they just need to be re-declared as real dependencies,
  not renamed.

### Stage 4 — Iterative compile-fix loop

- Run `javac`, capture diagnostics, bucket errors by type:
  - Unresolved symbol (usually a missed rename or wrong import)
  - Duplicate method (bridge method collision)
  - Incompatible types (raw type from lost generics)
  - Illegal forward reference
- Auto-patch the mechanical categories with small scripted passes:
  - Import insertion
  - Raw-type casts
  - Duplicate bridge method removal
- Recompile, repeat until convergence. Whatever's left after that goes to a
  human.

---

## Stack

- Java 17+, ASM 9.x for all bytecode work
- Vineflower + CFR + Procyon as Maven dependencies, invoked in-process
- Optionally build Stages 0–1 on top of **Recaf**'s plugin API instead of
  from scratch — it already has per-class isolation and pluggable
  decompilers, so the main original work becomes the rename/collision pass
  and the compile-fix loop

---

## Rough effort estimate

| Stage | Effort |
|---|---|
| 0 — ASM normalization pass | 1–2 days |
| 1 — Decompiler harness w/ isolation & fallback | 1–2 days (less on top of Recaf) |
| 2 — Output selection | 0.5 day |
| 3 — Resource/build scaffolding | 0.5–1 day |
| 4 — Compile-fix loop | 2–4 days, scales with how many distinct error shapes show up |

**Total: roughly 1–2 weeks** for a solid first version, tuned against a real
jar to calibrate Stage 4's error-bucketing.
