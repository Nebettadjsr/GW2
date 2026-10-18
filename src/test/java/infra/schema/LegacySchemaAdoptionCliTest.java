package infra.schema;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-INFRA-004 acceptance criteria 3, 5 and 6: the operator-facing side of adoption.
 *
 * <p>The adoption itself is substituted here, because what is under test is the command: which
 * request the arguments produce, that a refusal exits non-zero and prints the recovery instructions,
 * and that the printed report actually names the preserved inventory, the one proven association and
 * every unresolved source. The real adoption behavior is covered against PostgreSQL in
 * {@link LegacySchemaAdoptionTest}.
 */
class LegacySchemaAdoptionCliTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private int run(List<String> args,
                    java.util.function.Function<LegacySchemaAdoption.Request, LegacySchemaAdoption.Outcome> adopt) {
        return LegacySchemaAdoptionCli.run(args.toArray(String[]::new),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                adopt);
    }

    @Test
    void theArgumentsBecomeTheAdoptionRequestAndTheReportNamesWhatHappened() {
        var captured = new LegacySchemaAdoption.Request[1];

        int exit = run(List.of("--backup=/backups/gw2-pre-cutover.dump",
                "--writers-stopped=maintainer", "--schema=live", "--archive-schema=keepsafe", "--confirm"),
                request -> {
                    captured[0] = request;
                    return outcome();
                });

        assertEquals(LegacySchemaAdoptionCli.EXIT_OK, exit);
        assertEquals("/backups/gw2-pre-cutover.dump", captured[0].backupReference());
        assertEquals("maintainer", captured[0].writersStoppedAttestedBy());
        assertEquals("live", captured[0].liveSchema());
        assertEquals("keepsafe", captured[0].archiveSchema());
        assertTrue(captured[0].confirmed());

        String report = out();
        assertTrue(report.contains("account_bank: 3 rows"), report);
        assertTrue(report.contains("2 indistinguishable-slot rows"),
                "records the inventory rows awaiting explicit reconciliation: " + report);
        assertTrue(report.contains("LUCK: ASSOCIATED -> 11111111-2222-3333-4444-555555555555"), report);
        assertTrue(report.contains("BANK: UNRESOLVED"),
                "an unrecoverable ownership association must be reported, not hidden: " + report);
        assertTrue(report.contains("account_luck"), "the supported variants must be reported: " + report);
        assertTrue(report.contains("archive matches the import manifest"), report);
    }

    @Test
    void theDefaultArchiveSchemaIsUsedWhenNoneIsNamed() {
        var captured = new LegacySchemaAdoption.Request[1];

        run(List.of("--backup=b", "--writers-stopped=w", "--confirm"), request -> {
            captured[0] = request;
            return outcome();
        });

        assertEquals(LegacySchemaAdoption.DEFAULT_ARCHIVE_SCHEMA, captured[0].archiveSchema());
        assertEquals(null, captured[0].liveSchema(), "no --schema means the connection's default schema");
    }

    @Test
    void anUnknownArgumentFailsWithUsageInsteadOfAdopting() {
        int exit = run(List.of("--force"), request -> {
            throw new AssertionError("adoption must not be attempted for an unparsed command line");
        });

        assertEquals(LegacySchemaAdoptionCli.EXIT_USAGE, exit);
        assertTrue(err().contains("Unknown argument: --force"), err());
        assertTrue(err().contains("--writers-stopped="), err());
    }

    @Test
    void aRefusalExitsNonZeroAndPrintsTheRecoveryInstructions() {
        int exit = run(List.of("--confirm"), request -> {
            throw new LegacyAdoptionBlockedException("Legacy adoption refused before any write:",
                    List.of("no restorable backup reference was supplied"));
        });

        assertEquals(LegacySchemaAdoptionCli.EXIT_REFUSED, exit);
        assertTrue(err().contains("no restorable backup reference was supplied"), err());
        assertTrue(err().contains("restorable database backup"),
                "the refusal must state the prerequisite it is about: " + err());
        assertTrue(err().contains("compatible application version"),
                "the recovery boundary must be stated: " + err());
    }

    @Test
    void aFailedAdoptionReportsItsCause() {
        int exit = run(List.of("--backup=b", "--writers-stopped=w", "--confirm"), request -> {
            throw new SchemaGateException("could not record the baseline",
                    new IllegalStateException("history table is locked"));
        });

        assertEquals(LegacySchemaAdoptionCli.EXIT_REFUSED, exit);
        assertTrue(err().contains("could not record the baseline"), err());
        assertTrue(err().contains("history table is locked"), err());
    }

    private LegacySchemaAdoption.Outcome outcome() {
        return new LegacySchemaAdoption.Outcome(
                UUID.fromString("99999999-8888-7777-6666-555555555555"),
                LegacySchemaFingerprint.FINGERPRINT_ID,
                List.of("account_luck"),
                List.of("account_sync_state"),
                List.of(new LegacySchemaAdoption.ManifestEntry("account_bank", 3, "d1", null, null, 0, 0),
                        new LegacySchemaAdoption.ManifestEntry("character_items", 3, "d2", null, null, 2, 1)),
                List.of(new LegacySchemaAdoption.SourceOutcome("LUCK", LegacySchemaAdoption.ASSOCIATED,
                                "11111111-2222-3333-4444-555555555555", 1, "the row stored this GUID itself"),
                        new LegacySchemaAdoption.SourceOutcome("BANK", LegacySchemaAdoption.UNRESOLVED,
                                null, 3, "account_bank has no account column")),
                List.of("archive matches the import manifest for 2 object(s)"),
                false);
    }
}
