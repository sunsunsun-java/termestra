package dev.termestra.marketplace.adapter.in.http;

import dev.termestra.marketplace.application.MarketplaceCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
public final class MarketplaceController {
    private final MarketplaceCatalog catalog;

    public MarketplaceController(MarketplaceCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/marketplace/manifest")
    Mono<Map<String, Object>> manifest(@RequestParam(required = false) String lang) {
        return blocking(() -> catalog.manifest(lang));
    }

    @GetMapping("/api/marketplace/agent")
    Mono<MarketplaceCatalog.AgentDetail> agent(@RequestParam(required = false) String lang,
                                                @RequestParam(required = false) String path) {
        if (path == null || path.isBlank()) {
            return Mono.error(new IllegalArgumentException("Missing path parameter"));
        }
        return blocking(() -> catalog.agent(lang, path));
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }
}
