package dev.termestra.marketplace.adapter.out.classpath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClasspathMarketplaceCatalogTest {
    @Test
    void parsesCrLfFrontmatterFromAClasspathResource() {
        var agent = ClasspathMarketplaceCatalog.parse("design/ux.md", """
                ---\r
                name: UX Architect\r
                description: null\r
                ---\r
                # ArchitectUX\r
                """);

        assertEquals("UX Architect", agent.frontmatter().get("name"));
        assertNull(agent.frontmatter().get("description"));
        assertEquals("# ArchitectUX\n", agent.body());
    }
}
