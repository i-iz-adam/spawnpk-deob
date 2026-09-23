# HANDOVER — clean-decompile / SpawnPK deob (2026-09-23, evening)

## Where things stand

Full pipeline: 10472 classes (1129 in scope), Stage 1 **0 stubs**
(Vineflower, Client needs 120s budget), Stage 4 loop with per-fixer
logging + equilibrium stop. Latest: **~221 errors** (peak 320 uncapped;
the old "11" was partial javac attribution, not truth).

`spk_map.json` mappings verified live: `rs.Configuration` + `port`,
`Client.sendChatMessage` (real body), `Client.eventBus`.

## Resolved this session

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

## Remaining ~221: decompiler-precision fallout

Top: method-ref inference (~20), Consumer/guava composition,
raw-vs-generic override shapes (`method1327`), singles
(`split/exists/mkdir/indexOf/toCharArray` on Object vars,
`method1240/4551`, `Class1024→Map`, `Object+int`, `String>String`
leftovers). Next levers, in order:
1. **CFR backend** (second opinion; Stage 2 selection exists).
   Evidence 2026-09-23: the method-ref failures are Vineflower
   lambda mis-reductions, NOT missing generics — e.g. Class991
   `anyMatch(Class991::method2029)` where method2029 takes
   `(double, double, Shape)` (capturing-lambda collapsed to a bad
   static ref). Class generics (`<T extends Shape>`) survive fine,
   so bytecode-Signature restoration is NOT the lever. CFR renders
   invokedynamic/lambdas independently — likely nails these ~20.
   CFR probe DONE (subagent, 2026-09-23): `CfrDriver.Builder`
   + custom `ClassFileSource` (bytes via provider) + `OutputSinkFactory`
   capturing `SinkReturns.Decompiled.getJava()`; `withOptions(Map.of())`
   = defaults; fresh driver per call (stateless, timeout-safe).
   NOTE: wiring CFR as fallback alone changes nothing (Vineflower
   succeeds everywhere) — must ALSO rewire Stage 1 to run all backends
   per class and let OutputSelector pick (now single-backend-wins).
   That doubles stage-1 time (~10 min/run).
2. Human passes per `stage4-fix-loop-report.json`.

## What's next

1. CFR backend + Stage 1 multi-backend selection (probe above).
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
