package dev.termestra.bootstrap.support;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesPtyFixtureTest {
    @Test
    void separatesCookedSubmissionsAtTheirCompleteMessageBoundaries() throws Exception {
        String startup = "\u001b[200~<termestra-message kind=\"startup\">\nsetup\n"
                + "</termestra-message>\u001b[201~\r\n";
        String task = "\u001b[200~HERMES_DELIVERY_TOKEN\n<termestra-system-reminder>\n"
                + "instructions\n</termestra-system-reminder>\u001b[201~\r\n";
        ByteArrayInputStream input = new ByteArrayInputStream(
                (startup + task).getBytes(StandardCharsets.UTF_8));

        String first = new String(HermesPtyFixture.readCookedSubmission(input),
                StandardCharsets.UTF_8);
        String second = new String(HermesPtyFixture.readCookedSubmission(input),
                StandardCharsets.UTF_8);

        assertTrue(first.contains("<termestra-message kind=\"startup\">"));
        assertTrue(first.contains("</termestra-message>"));
        assertTrue(second.contains("HERMES_DELIVERY_TOKEN"));
        assertTrue(second.contains("</termestra-system-reminder>"));
        assertNull(HermesPtyFixture.readCookedSubmission(input));
    }
}
