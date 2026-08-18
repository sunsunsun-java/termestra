package dev.termestra.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticUiIntegrationTest {
    private static final Path DATA = temporaryDirectory();
    @LocalServerPort int port;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("termestra.data-directory", DATA::toString);
    }

    @Test void servesTheBundledReactApplicationAndPublicVersionProbe() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        client.get().uri("/").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/html")
                .expectBody(String.class).value(html -> {
                    if (!html.contains("<title>Termestra</title>") || !html.contains("/assets/index-")) {
                        throw new AssertionError("bundled Termestra UI is missing");
                    }
                });
        client.get().uri("/api/version").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.package_name").isEqualTo("@termestra/cli")
                .jsonPath("$.install_hint").isEqualTo("termestra update");
        client.get().uri("/sw.js").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store");
        client.get().uri("/manifest.webmanifest").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "max-age=0, must-revalidate");
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("termestra-static-ui-"); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
}
