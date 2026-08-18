package dev.termestra.platform.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VersionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC);

    @Test void reportsAndCachesANewerRegistryVersion() {
        AtomicInteger calls = new AtomicInteger();
        VersionService service = new VersionService("1.4.0", () -> { calls.incrementAndGet(); return "2.1.19"; }, CLOCK);
        assertEquals("2.1.19", service.get().get("latest_version"));
        assertEquals(true, service.get().get("update_available"));
        assertEquals(1, calls.get());
    }

    @Test void fallsBackToTheInstalledVersionWhenTheRegistryIsUnavailable() {
        VersionService service = new VersionService("1.4.0", () -> { throw new java.io.IOException("offline"); }, CLOCK);
        assertEquals("1.4.0", service.get().get("latest_version"));
        assertEquals(false, service.get().get("update_available"));
    }

    @Test void comparesReleaseAndPrereleaseVersions() {
        assertTrue(VersionService.compare("2.0.0", "1.9.9") > 0);
        assertTrue(VersionService.compare("2.0.0", "2.0.0-beta.1") > 0);
        assertTrue(VersionService.compare("2.0.0-beta.10", "2.0.0-beta.2") > 0);
        assertTrue(VersionService.compare("2.0.0-2", "2.0.0-beta") < 0);
        assertEquals(0, VersionService.compare("2.0.0+build.1", "2.0.0+build.2"));
        assertTrue(VersionService.compare("2147483648.0.0", "2147483647.0.0") > 0);
    }

    @Test void boundsTheExternalRegistryResponseBeforeDecodingIt() {
        byte[] oversized = new byte[VersionService.MAX_REGISTRY_RESPONSE_BYTES + 1];

        java.io.IOException error = assertThrows(java.io.IOException.class,
                () -> VersionService.readBoundedRegistryResponse(new ByteArrayInputStream(oversized)));

        assertTrue(error.getMessage().contains("response exceeds"));
    }
}
