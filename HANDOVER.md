# HANDOVER — clean-decompile / SpawnPK deob (2026-09-23, evening)

## Where things stand

Full pipeline: 10472 classes (1129 in scope), Stage 1 **0 stubs**
(Vineflower, Client needs 120s budget), Stage 4 loop with per-fixer
logging + equilibrium stop. Latest: **~221 errors** (peak 320 uncapped;
the old "11" was partial javac attribution, not truth).

`spk_map.json` mappings verified live: `rs.Configuration` + `port`,
`Client.sendChatMessage` (real body), `Client.eventBus`.

## Resolved this session

- **CFR backend wired** (`CfrDecompiler`: `CfrDriver.Builder` +
  in-memory `ClassFileSource` + capture sink; MULTIVER sink, not
  DECOMPILED). JDK platform classes served from `jrt:/` (`JdkClasses`)
  so CFR stops degrading to Object. Stage 1 runs all backends per
  class, `OutputSelector` picks (residue/Object/method-ref-arity
  signals, all unit-tested incl. fixture regression test).
- **Swap rounds** (orchestrator): failing files retried with untried
  backends, kept only on strict whole-tree improvement, else reverted
  (bounded 2 rounds, equilibrium stop, per-fixer logging). Honest
  negative on SPK: 68 files retried, all reverted — alternates don't
  beat first picks here. Mechanism stays for Procyon/other jars.
- Trove/applet/lombok/eawt: all zero (clash repair, release 11,
  lombok jar, eawt shims).

- **Trove: ZERO.** `LibraryClashRepair` renames the type side of
  library type/package clashes (`gnu/trove/f` -> `gnu/trove/f_`,
  85 cases incl. jackson + top-level `a`), merged into the global
  remap. Synthetic-jar tested.
- **Lombok: ZERO.** Pinned `lib/lombok-1.18.32.jar` (committed,
  gitignore-excepted); `--lombok-jar` on Stage 4's cp (`-proc:none`);
  Gradle gets compileOnly + annotationProcessor when used.
- **Applet: ZERO** via `--release-level 11`. **eawt: ZERO** via
  committed `stubs/com/apple/eawt/*` + generic `--extra-sources`.
- **Annotation elements** never renamed (string-referenced usages).
- **Inherited-owner refs**: rename maps propagate to subclasses.
- **Synthetic flag strip** (in-scope): obfuscator sets ACC_SYNTHETIC
  on real members; decompilers drop them. Bridge kept.
- Stage 4 fixers: imports (dup-guarded), concat (multi-line), raw
  casts (+bogus-strip, anti-stack), artifacts (paren/capture),
  String compareTo, access widening (private->public), receiver casts
  (curated JDK map). `-Xmaxerrs 5000`. Loop stops on diagnostic-set
  equilibrium. Per-fixer counts print each iteration.

## Remaining ~280: decompiler-precision fallout (CFR wins 50 files,
37 clean; its 13 dirty ones carry ~155, Client dominant)

Top: method-ref inference (~20), Consumer/guava composition,
raw-vs-generic override shapes (`method1327`), singles
(`split/exists/mkdir/indexOf/toCharArray` on Object vars,
`method1240/4551`, `Class1024→Map`, `Object+int`, `String>String`
leftovers). Next levers, in order:
1. Procyon backend (third opinion; swap machinery already handles it).
2. Human passes per `stage4-fix-loop-report.json`.
(Ruled out: bytecode-Signature restoration -- method-ref failures are
Vineflower lambda mis-reductions, e.g. Class991 collapsing a capturing
lambda into wrong-arity static refs; class generics survive fine.)

## What's next

1. Procyon backend (swap machinery handles new backends automatically).
2. `gradle build` in `out\src-generated` after human fixes; `run.bat`.
3. Grow `spk_map.json` from manifest (`global-unique-name` = unnamed).
4. `--decompile-libraries` full-run validation on client.jar (synthetic
   only). Maven fingerprinting still stubbed.

## Commands

```
.\spk_deob.bat                                   # full run (~5 min)
rtk mvn -q -DskipTests package                   # rebuild
```

Repo: `i-iz-adam/spawnpk-deob` (public). `out/`, `target/`, `*.jar`
(except `lib/lombok-*`), real mappings gitignored.

## Gotchas

- Never transmit `\uXXXX` escapes through Edit (mojibake); use
  `(char) N` or raw chars; hex-dump on mismatch.
- `ctx_*` sandbox tools run bash, not PowerShell.
- javac caps at 100 errors by default; loop equilibrium > counts.
- Fixers must be idempotent, non-stacking, and count real writes.
- A "too good" rerun (11 vs 273) means partial attribution, not truth.
