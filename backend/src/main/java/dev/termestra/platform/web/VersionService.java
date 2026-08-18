package dev.termestra.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class VersionService {
    private static final String PACKAGE_NAME = "@termestra/cli";
    private static final long CACHE_MILLIS = Duration.ofHours(6).toMillis();
    static final int MAX_REGISTRY_RESPONSE_BYTES = 64 * 1024;
    private final String currentVersion;
    private final LatestVersionLookup latestVersion;
    private final Clock clock;
    private Cache cache;

    VersionService(String currentVersion, LatestVersionLookup latestVersion, Clock clock) {
        this.currentVersion = currentVersion;
        this.latestVersion = latestVersion;
        this.clock = clock;
    }

    static VersionService npm(String currentVersion, ObjectMapper objectMapper) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1_500)).build();
        return new VersionService(currentVersion, () -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://registry.npmjs.org/@termestra%2Fcli/latest"))
                    .timeout(Duration.ofMillis(1_500)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IllegalStateException("npm registry returned " + response.statusCode());
            }
            String body;
            try (InputStream input = response.body()) {
                body = readBoundedRegistryResponse(input);
            }
            String version = objectMapper.readTree(body).path("version").asText();
            if (version.isBlank()) throw new IllegalStateException("npm registry response did not include a version");
            return version;
        }, Clock.systemUTC());
    }

    static String readBoundedRegistryResponse(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_REGISTRY_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_REGISTRY_RESPONSE_BYTES) {
            throw new IOException("npm registry response exceeds " + MAX_REGISTRY_RESPONSE_BYTES + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    synchronized Map<String, Object> get() {
        long now = clock.millis();
        if (cache != null && cache.expiresAt > now) return cache.payload;
        String latest = currentVersion;
        try { latest = latestVersion.fetch(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (IOException | RuntimeException unavailable) { latest = currentVersion; }
        Map<String, Object> payload = Map.of(
                "current_version", currentVersion,
                "install_hint", "termestra update",
                "latest_version", latest,
                "package_name", PACKAGE_NAME,
                "release_url", "https://www.npmjs.com/package/" + PACKAGE_NAME + "/v/" + latest,
                "update_available", compare(latest, currentVersion) > 0);
        cache = new Cache(now + CACHE_MILLIS, payload);
        return payload;
    }

    static int compare(String left, String right) {
        Version a = Version.parse(left); Version b = Version.parse(right);
        for (int index = 0; index < 3; index++) {
            int difference = compareNumericIdentifier(a.core[index], b.core[index]);
            if (difference != 0) return difference;
        }
        if (a.prerelease.equals(b.prerelease)) return 0;
        if (a.prerelease.isEmpty()) return 1;
        if (b.prerelease.isEmpty()) return -1;
        String[] leftIdentifiers = a.prerelease.split("\\.", -1);
        String[] rightIdentifiers = b.prerelease.split("\\.", -1);
        int shared = Math.min(leftIdentifiers.length, rightIdentifiers.length);
        for (int index = 0; index < shared; index++) {
            String leftIdentifier = leftIdentifiers[index];
            String rightIdentifier = rightIdentifiers[index];
            if (leftIdentifier.equals(rightIdentifier)) continue;
            boolean leftNumeric = isNumeric(leftIdentifier);
            boolean rightNumeric = isNumeric(rightIdentifier);
            if (leftNumeric && rightNumeric) {
                int difference = compareNumericIdentifier(leftIdentifier, rightIdentifier);
                if (difference != 0) return difference;
            } else if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            } else {
                int difference = leftIdentifier.compareTo(rightIdentifier);
                if (difference != 0) return difference;
            }
        }
        return Integer.compare(leftIdentifiers.length, rightIdentifiers.length);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') return false;
        }
        return true;
    }

    private static int compareNumericIdentifier(String left, String right) {
        String normalizedLeft = stripLeadingZeroes(left);
        String normalizedRight = stripLeadingZeroes(right);
        int lengthComparison = Integer.compare(normalizedLeft.length(), normalizedRight.length());
        return lengthComparison == 0 ? normalizedLeft.compareTo(normalizedRight) : lengthComparison;
    }

    private static String stripLeadingZeroes(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') index++;
        return value.substring(index);
    }

    private record Cache(long expiresAt, Map<String, Object> payload) { }
    @FunctionalInterface interface LatestVersionLookup { String fetch() throws IOException, InterruptedException; }
    private record Version(String[] core, String prerelease) {
        static Version parse(String value) {
            String withoutBuild = value.split("\\+", 2)[0];
            String[] pieces = withoutBuild.split("-", 2); String[] rawCore = pieces[0].split("\\.");
            String[] numbers = {"0", "0", "0"};
            for (int index = 0; index < numbers.length && index < rawCore.length; index++) {
                String numericPrefix = rawCore[index].replaceFirst("[^0-9].*$", "");
                if (!numericPrefix.isEmpty()) numbers[index] = numericPrefix;
            }
            return new Version(numbers, pieces.length == 2 ? pieces[1] : "");
        }
    }
}
