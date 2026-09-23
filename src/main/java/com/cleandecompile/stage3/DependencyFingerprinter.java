package com.cleandecompile.stage3;

import com.cleandecompile.model.ClassInfo;

import java.security.MessageDigest;
import java.util.*;

/**
 * For every out-of-scope (bundled library) class, tries to identify which
 * real Maven artifact it came from by hashing, so Stage 3 can declare a
 * normal dependency instead of shipping raw {@code .class} files. Falls
 * back to vendoring whatever it can't identify.
 *
 * <p>Fingerprinting by hash only works when the bundler didn't shade/relocate
 * packages -- a shaded dependency's classes won't hash-match the upstream
 * artifact even though the code is identical, because package names
 * changed. There's no reliable way to reverse that without deobfuscating
 * the library itself, which is explicitly out of scope (that's what
 * {@link com.cleandecompile.scope.ScopeClassifier} exists to avoid doing).
 * So: identify what we can, vendor the rest, and never silently drop a
 * class either way.
 *
 * <p><b>TODO:</b> the actual Maven Central lookup. The approach: hash each
 * class body (excluding the constant pool's UTF8 entries for source file
 * names / debug info, which vary across otherwise-identical builds) and
 * check it against a local index built once from Maven Central's
 * {@code .sha1} sidecar files for common artifacts, OR shell out to a
 * service like https://search.maven.org's class-search if network access
 * to Maven infrastructure is available in the deployment environment. This
 * sandbox's network egress list doesn't include Maven Central, so that
 * lookup is stubbed to "unidentified" here -- {@link #KNOWN_ARTIFACT_HASHES}
 * is where a real index would be plugged in.
 */
public final class DependencyFingerprinter {

    public record IdentifiedArtifact(String groupId, String artifactId, String version) {}

    public record FingerprintResult(
            Map<String, IdentifiedArtifact> identifiedByInternalName,
            List<String> unidentifiedInternalNames
    ) {}

    /** internalName-class-hash -> artifact. Empty until a real Maven Central
     *  index is plugged in; see class javadoc. */
    private static final Map<String, IdentifiedArtifact> KNOWN_ARTIFACT_HASHES = Map.of();

    public FingerprintResult fingerprint(List<ClassInfo> outOfScopeClasses) throws Exception {
        Map<String, IdentifiedArtifact> identified = new LinkedHashMap<>();
        List<String> unidentified = new ArrayList<>();

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (ClassInfo ci : outOfScopeClasses) {
            String hash = HexFormat.of().formatHex(sha256.digest(ci.bytes()));
            sha256.reset();

            IdentifiedArtifact artifact = KNOWN_ARTIFACT_HASHES.get(hash);
            if (artifact != null) {
                identified.put(ci.internalName(), artifact);
            } else {
                unidentified.add(ci.internalName());
            }
        }
        return new FingerprintResult(identified, unidentified);
    }
}
