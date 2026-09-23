# clean-decompile

Turns an obfuscated (ProGuard-style) jar into the highest-percentage-compilable,
least-manually-patched Java source tree possible, without a single
decompiler exception halting the whole job.

Pipeline: Stage 0 bytecode normalization (scope-aware rename + frame repair)
-> Stage 1 multi-decompiler harness (Vineflower primary, CFR/Procyon TODO,
bytecode stub fallback) -> Stage 2 output selection -> Stage 3 Gradle
scaffolding + vendored libs -> Stage 4 javac compile-fix loop.

## Status

| Stage | State |
|---|---|
| 0 — Bytecode normalization | Implemented. Scope-aware renames (classes, packages, fields, methods), override-safe method families, `minKeepableLength` keep heuristic (short junk renamed, long genuine names kept), custom-name overrides, frame repair. |
| 1 — Multi-decompiler harness | **Vineflower + CFR wired (in-process, per-class timeouts, full-jar + JDK library context).** Procyon backend still a stub. Every backend runs per class; `--decompile-libraries` unaffected. |
| 2 — Output selection | Heuristic pick (raw/synthetic/residue/Object/method-ref-arity signals) plus **swap rounds**: failing files are retried with untried backends and the swap is kept only if whole-tree errors strictly drop (reverts otherwise). Formatting pass still TODO. |
| 3 — Resource & build scaffolding | Implemented. Libraries vendored into `src-generated/libs/`, Gradle build at release level, `run.bat` when `--main-class` given. Dependency fingerprinting against Maven Central is a stub. |
| 4 — Compile-fix loop | Real javac loop: import insertion, string-concat/bootstrap rewrite, raw casts, decompiler artifacts, String compareTo, access widening, receiver casts. Equilibrium stop, per-fixer logging. Bridge-method removal still TODO. |

## Building

```
mvn -q package
```

## Usage 1 — basic obfuscated jar

Scope the tool to your own packages (`--own-package`, repeatable).
Everything else is treated as bundled library: left un-renamed, vendored as
bytecode under `src-generated/libs/`.

```
java -jar target\clean-decompile.jar --jar client.jar --output out --own-package rs
```

Without `--own-package` the whole jar is treated as owned code.

## Usage 2 — mapping file (known names)

Copy the example and fill in what you know. Dotted names for
packages/classes; `Owner#member` + JVM descriptor for fields/methods.
Short (1-2 char) names are auto-renamed anyway; mappings pin the ones you
want to choose yourself.

```
copy spk_map.example.json spk_map.json
java -jar target\clean-decompile.jar --jar client.jar --output out ^
  --own-package rs --custom-names spk_map.json
```

`spk_map.json` is gitignored (private knowledge); the example is committed.

### Expected deobfuscated result (SpawnPK client)

| Obfuscated | Deobfuscated | Where |
|---|---|---|
| `rs.f.a` | `rs.Configuration` | `src-generated/src/main/java/rs/Configuration.java` |
| `rs.f.a#b:Integer` (`43594`) | `port` | same file: `public static final Integer port = 43594;` |
| `rs.Client#a(String,int,String)` | `sendChatMessage` | `src-generated/src/main/java/rs/Client.java` |
| `rs.Client#p:EventBus` | `eventBus` | same file |

Field/method keys need exact JVM descriptors — verify with
`javap -p -classpath client.jar rs.Client`. A custom entry that collides
with a hard-protected member (native, serialization magic, `main`,
external override) is reported in `manifests/stage0-rename-manifest.json`
as `custom-override-ignored-hard-protected`, except external-touch poison
which an explicit mapping overrides.

## Usage 3 — old platform + run (SpawnPK)

`spk_deob.bat` runs the full SpawnPK configuration: `rs` scope, the
mapping file, a long decompiler timeout (the huge `Client` class needs
~60s), release 11 (Temurin 11 JRE still has `java.applet`), main class
and JRE for the generated run script.

```
.\spk_deob.bat
```

Equivalent manual flags: `--decompile-timeout-ms 120000 --release-level 11`
`--main-class rs.Client --jre-home "C:\Users\naxos\AppData\Local\SpawnPK\jre"`.

After clearing Stage 4's remaining errors: `gradle build` in
`out\src-generated`, then launch with `out\src-generated\run.bat`
(uses the configured JRE).

## Useful flags

```
--decompile-libraries   also decompile bundled libs to source (original
                        names). Default off: vendored bytecode only. Slow
                        (~30-60 min on big jars) and third-party sources
                        may not compile standalone.
--release-level 11      javac --release for Stage 4 + Gradle (default 17)
--main-class rs.Client  write run.bat launching this class
--jre-home <path>       java used by run.bat (default: PATH)
--lombok-jar lib/...jar Lombok on Stage 4's javac classpath; Gradle gets
                        compileOnly + annotationProcessor when sources use it
--extra-sources stubs/  hand-written .java shims copied into the tree
                        (e.g. stubs/com/apple/eawt/* for the Mac-only API)
```

## Output layout under `--output`

```
out/
  stage0-normalized.jar          # renamed, frame-repaired
  src-generated/
    src/main/java/...            # decompiled .java (Vineflower) or stubs
    src/main/resources/...       # non-class resources
    libs/vendored-unidentified.jar
    build.gradle settings.gradle run.bat
  manifests/
    stage0-rename-manifest.json  # every rename/keep decision + reason
    stage1-decompile-manifest.json
    stage4-fix-loop-report.json  # remaining errors for a human
```

## Known limitations (SpawnPK client)

- Out-of-scope types sharing a path with a package (shaded trove's
  `gnu/trove/f` interface vs `gnu/trove/f/` package, 85 cases) are auto
  repaired: the type keeps its package and gains a suffix (`f_`), recorded
  as `library-package-clash` in the manifest. Zero trove errors remain.
- `com.apple.eawt` (Mac-only, absent everywhere): minimal source shims in
  `stubs/`, pulled in via `--extra-sources`. Zero eawt errors remain.
- Lombok-annotated sources: `--lombok-jar` (pinned under `lib/`) for
  Stage 4 symbols, Gradle processor wiring when used. Zero lombok errors.
- Remaining ~220 errors are decompiler-precision fallout (method-ref and
  generics inference, raw-vs-generic override shapes like `method1327`,
  a few single-site artifacts) -- the manifest lists each; CFR backend or
  human passes own them.
- CFR/Procyon backends unwired; Maven fingerprinting stubbed (0 identified).
