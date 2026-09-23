# HANDOVER — clean-decompile / SpawnPK deob (2026-09-23)

## Where things stand

Full pipeline runs end-to-end on `client.jar` (10472 classes, 1129 in
scope under `--own-package rs`). Latest run (`.\spk_deob.bat`):
Stage 0: 1102 class/package renames, 12726 field/method renames, 6593 warnings.
Stage 1: **1128 Vineflower + Client (120s budget) = 0 stubs.**
Stage 4: loop fires (concat fixer), remainder is input-inherited (below).

`spk_map.json` mappings verified live in output: `rs.Configuration`
with `port = 43594`, `Client.sendChatMessage` with real body,
`Client.eventBus`.

## Remaining errors (~100, javac caps display at 100)

| Bucket | Count | Verdict |
|---|---|---|
| trove package/interface clashes (`gnu.trove.f/i/e` + fallout `i/cc/bU/k/M` in rs files) | ~70 | Inherited from input jar. Interface and package share a name; javac picks the type, source refs die. Bytecode links fine. Needs trove-package rename or canonical-trove swap (human decision). |
| `com.apple.eawt[.event]` | 3 | Mac-only API, absent. Stub the 2-3 files or drop them. |
| `Class961` missing abstract `apply` | 1 | Bytecode-concrete, source-incomplete. Mark abstract by hand. |
| `lombok` import | 2 | One file references lombok (absent). Check if genuine or Vineflower artifact. |

`java.applet` errors are GONE since `--release-level 11` (Temurin 11 JRE).

## Decisions made this session (don't re-litigate blindly)

1. `minKeepableLength=3`: segments/members shorter always renamed; longer + legal kept (`Client`, `cache`, `gui`, `eventBus` survive). All `$` simple names always renamed (top-level stub/source layout can't nest).
2. Override-poison split: HARD poison (native, serialization, `main`, out-of-scope member) vetoes even custom mappings; heuristic external-touch poison yields to explicit `spk_map.json` entries. Missing JDK ancestors resolve via runtime `Class.forName` check (closed-world for `java.lang.Object`).
3. `BytecodeNormalizer` propagates renamed names into `ClassInfo` (jar entries, Stage 1 paths, stub headers were stale before).
4. `VineflowerDecompiler` in-process (`BaseDecompiler` + in-memory source + normalized jar as library + capture sink). Empty options map = defaults (probe-verified). CFR/Procyon still stubs.
5. `JavacRunner` uses `--release <level>` + vendored jar on classpath. `BuildScaffolder` vendors into `src-generated/libs/`, writes release-aware `build.gradle` + `run.bat` (needs `--main-class`).
6. `StringConcatFixer` (Stage 4) rewrites Vineflower's leaked `StringConcatFactory.makeConcatWithConstants<...>` to `+` chains. Conservative skip on shape mismatch.
7. Deps default = vendored bytecode jars (`--decompile-libraries` opt-in for full lib sources; never full-run on client.jar — 30-60 min and trove sources won't compile).
8. New files: `spk_deob.bat`, `spk_map.json` (gitignored), `spk_map.example.json` (committed).

## What's next (priority order)

1. Trove strategy (blocks compilation): rename trove packages in vendoring, or swap canonical trove + remap refs. Biggest single win (~70 errors).
2. eawt/lombok/Class961 hand-fixes (5 min each, listed above).
3. `gradle build` in `out\src-generated`, then `run.bat` (Temurin 11) — first real launch attempt.
4. Grow `spk_map.json` from `stage0-rename-manifest.json` (`global-unique-name` entries = unnamed).
5. Wire CFR backend (second opinion for Vineflower timeouts/failures).
6. `--decompile-libraries` full-run validation on client.jar (only synthetic-tested).
7. Maven fingerprinting (currently 0 identified, no network in sandbox).

## Commands

```
.\spk_deob.bat                                   # full SpawnPK run
& "...\corretto-26.0.1\bin\java.exe" -jar target\clean-decompile.jar --help
rtk mvn -q -DskipTests package                   # rebuild
```

Repo: `i-iz-adam/spawnpk-deob` (public). `out/`, `target/`, `*.jar`, real mappings gitignored.

## Gotchas for the next session

- `out/` is wiped per run design (Stage 1 cleans its tree); always full reruns (~5 min, Client needs the 120s timeout).
- Never transmit `\uXXXX` escapes through the Edit tool (mojibake); use `Character.toString((char) N)` or raw chars.
- `ctx_*` sandbox tools run bash, not PowerShell — use `default.bash` for PS commands.
- Stage 4 report truncates at javac's 100-error cap; composition shifts as syntax errors clear.
