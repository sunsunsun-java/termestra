package dev.termestra.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
public final class VersionController {
    private final VersionService versions;

    @Autowired
    public VersionController(@Value("${termestra.version:0.1.0-SNAPSHOT}") String version,
                             ObjectMapper objectMapper) {
        this.versions = VersionService.npm(version, objectMapper);
    }

    VersionController(VersionService versions) { this.versions = versions; }

    @GetMapping("/api/version")
    public Mono<Map<String, Object>> version() {
        return Mono.fromCallable(versions::get).subscribeOn(Schedulers.boundedElastic());
    }
}
