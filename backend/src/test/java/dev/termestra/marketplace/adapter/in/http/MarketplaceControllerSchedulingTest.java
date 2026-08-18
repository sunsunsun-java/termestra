package dev.termestra.marketplace.adapter.in.http;

import dev.termestra.marketplace.application.MarketplaceCatalog;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MarketplaceControllerSchedulingTest {
    @Test void classpathReadsRunOutsideTheNettyEventLoop() {
        RecordingCatalog catalog = new RecordingCatalog();
        MarketplaceController controller = new MarketplaceController(catalog);
        Thread caller = Thread.currentThread();
        String originalName = caller.getName();

        try {
            caller.setName("reactor-http-nio-test");
            assertEquals("zh", controller.manifest("zh")
                    .block(Duration.ofSeconds(2)).get("language"));
            assertFalse(catalog.threadName.get().startsWith("reactor-http-nio"));
        } finally {
            caller.setName(originalName);
        }
    }

    private static final class RecordingCatalog implements MarketplaceCatalog {
        private final AtomicReference<String> threadName = new AtomicReference<>();

        @Override public Map<String, Object> manifest(String language) {
            threadName.set(Thread.currentThread().getName());
            return Map.of("language", language);
        }

        @Override public AgentDetail agent(String language, String path) {
            threadName.set(Thread.currentThread().getName());
            return new AgentDetail(path, Map.of(), "");
        }
    }
}
