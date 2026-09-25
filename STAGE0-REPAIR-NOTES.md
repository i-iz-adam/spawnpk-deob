# Stage 0 lambda/bridge repair (steps 1-2 of the reduction plan)

## What changed
- **`stage0/CompilerArtifactAnalysis.java`** (new): structurally detects, from
  bytecode shape alone (not names), which in-scope methods are javac-generated
  lambda bodies (targets of an `invokedynamic`/`LambdaMetafactory` call in the
  same class) and which are compiler bridge methods (a body that loads `this`
  and each argument, casts where needed, forwards to one other method of the
  same class, and returns -- and which overrides something).
- **`stage0/BytecodeNormalizer.java`**: after its existing blanket
  `ACC_SYNTHETIC` strip, restores `ACC_SYNTHETIC` on real lambda bodies and
  `ACC_SYNTHETIC|ACC_BRIDGE` on real bridges, using the analysis above. Both
  Vineflower and CFR key their "hide/inline this" behavior off these flags,
  not the method name, which is why the blanket strip was breaking them.
- **`stage0/MemberRenamePlanner.java`**: new `unifyBridgeTargetNames` pass.
  When a bridge and its real target got split into two override families
  (because generic erasure means they don't share a descriptor, so the
  existing `MethodOverrideGroups` analysis can't link them), this renames the
  target's whole override family to the bridge's name -- the only name
  source can express for that pair. Guarded against protected families,
  user-pinned names, conflicting bridges, and name collisions; anything
  skipped is written to a new `plannerNotes` list in the Stage 0 manifest.
- **`stage0/MethodOverrideGroups.java`**: exposes `overridesSomething`, reused
  by the analysis above to avoid flagging a private helper that just happens
  to be called from one place.
- **`model/MemberRenameEntry.java`**: new `REASON_BRIDGE_TARGET` reason code.
- **`stage0/Stage0Runner.java`**: wires the analysis in before renaming, and
  writes `plannerNoteCount`/`plannerNotes` into the Stage 0 manifest.

## Verification performed (see conversation for full detail)
- The entire `src/main/java` tree compiles cleanly against real ASM 9.x
  (rebuilt locally from the JDK's own bundled copy, since Maven Central
  wasn't reachable from the sandbox), real Vineflower 1.10.1, real CFR
  0.152, picocli, and google-java-format.
- All 22 pre-existing tests pass unchanged.
- End-to-end synthetic reproduction: a hand-built class with a capturing
  lambda and a generic `Loader<K,V>`/bridge pair, obfuscated the same way
  the real jar is (flags stripped, target renamed independently of its
  bridge). Decompiling the *unpatched* normalization with real Vineflower
  reproduces all four documented error shapes verbatim (invalid method
  reference w/ dropped captures, "not abstract and does not override",
  "name clash ... have the same erasure", "cannot find symbol" on a
  formerly-captured parameter). Decompiling the *patched* normalization of
  the same input compiles with `javac`, 0 errors.

## Not done yet (next session)
- Step 3: `-XDshould-stop.ifError=FLOW` in `stage4/JavacRunner.java` (javac
  currently hides flow-analysis errors -- missing returns, uninitialized
  variables, most unreported exceptions -- behind any attribution error in
  the same file, so the Stage 4 report undercounts real errors), plus making
  `stage4/CompileFixLoop.java`'s per-file fixer application transactional
  (snapshot/apply/recompile/revert per file) so a regressing fixer can't
  silently make a file worse.
- Unit tests for `CompilerArtifactAnalysis` itself (lambda detection, bridge
  detection, and the "weak candidate" method-reference carve-out).
- Steps 4-7 from the original proposal (compile-verified Stage 1 output
  selection to replace the regex heuristics; decompiler option tuning for
  CFR/Vineflower/target release level; Procyon wiring or removal; smaller
  Stage 4 fixers; local-variable slot-splitting investigation for the
  remaining CFR-selected-class errors).
