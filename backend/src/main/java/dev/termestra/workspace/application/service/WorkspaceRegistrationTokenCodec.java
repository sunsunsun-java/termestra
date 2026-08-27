package dev.termestra.workspace.application.service;

import dev.termestra.workspace.application.port.out.GitWorktreeAccess;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

public final class WorkspaceRegistrationTokenCodec {
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(10);
    private final byte[] key;
    private final Clock clock;

    public WorkspaceRegistrationTokenCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.key = new byte[32];
        new SecureRandom().nextBytes(key);
    }

    public String issuePath(String canonicalPath) {
        return sign("P\0" + expiresAt() + "\0" + canonicalPath);
    }

    public String requirePath(String token) {
        String[] fields = verified(token, "P", 3);
        return fields[2];
    }

    public String issueSelection(GitWorktreeAccess.Inspection inspection,
                                 GitWorktreeAccess.LocalBranch branch) {
        return sign(String.join("\0",
                "S",
                Long.toString(expiresAt()),
                inspection.worktreeRoot(),
                inspection.commonGitDirectory(),
                headKind(inspection.head()),
                headName(inspection.head()),
                headOid(inspection.head()),
                branch.name(),
                branch.oid(),
                Boolean.toString(branch.checkedOutElsewhere())));
    }

    public void requireSelection(String token, GitWorktreeAccess.Inspection inspection,
                                 GitWorktreeAccess.LocalBranch branch) {
        String[] fields = verified(token, "S", 10);
        String expected = String.join("\0",
                "S", fields[1], inspection.worktreeRoot(), inspection.commonGitDirectory(),
                headKind(inspection.head()), headName(inspection.head()), headOid(inspection.head()),
                branch.name(), branch.oid(), Boolean.toString(branch.checkedOutElsewhere()));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                String.join("\0", fields).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Git selection is stale");
        }
    }

    private long expiresAt() {
        return clock.instant().plus(TOKEN_LIFETIME).getEpochSecond();
    }

    private String[] verified(String token, String expectedKind, int expectedFields) {
        if (token == null || token.isBlank() || token.length() > 16_384) {
            throw new IllegalArgumentException("Inspection token is invalid");
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new IllegalArgumentException("Inspection token is invalid");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(token.substring(0, dot));
            byte[] signature = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(signature, hmac(payload))) {
                throw new IllegalArgumentException("Inspection token is invalid");
            }
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\0", -1);
            if (fields.length != expectedFields || !expectedKind.equals(fields[0])) {
                throw new IllegalArgumentException("Inspection token has the wrong scope");
            }
            long expiry = Long.parseLong(fields[1]);
            if (clock.instant().getEpochSecond() > expiry) {
                throw new IllegalArgumentException("Inspection token has expired");
            }
            return fields;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Inspection token is invalid", error);
        }
    }

    private String sign(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + '.'
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(bytes));
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String headKind(GitWorktreeAccess.Head head) {
        if (head instanceof GitWorktreeAccess.BranchHead) return "branch";
        if (head instanceof GitWorktreeAccess.DetachedHead) return "detached";
        return "unborn";
    }

    private static String headName(GitWorktreeAccess.Head head) {
        if (head instanceof GitWorktreeAccess.BranchHead value) return value.name();
        if (head instanceof GitWorktreeAccess.UnbornHead value) return value.name();
        return "";
    }

    private static String headOid(GitWorktreeAccess.Head head) {
        if (head instanceof GitWorktreeAccess.BranchHead value) return value.oid();
        if (head instanceof GitWorktreeAccess.DetachedHead value) return value.oid();
        return "";
    }
}
