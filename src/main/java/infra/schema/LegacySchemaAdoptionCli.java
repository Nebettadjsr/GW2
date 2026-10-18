package infra.schema;

import repo.AppConfig;

import java.io.PrintStream;
import java.util.function.Function;

/**
 * The explicitly invoked operator entry point for {@link LegacySchemaAdoption}.
 *
 * <p>Separate from every serving entry point on purpose: adopting a legacy database is a reviewed,
 * one-time operation with prerequisites, not something ordinary startup may decide to do
 * (TARGET_ARCHITECTURE.md 7.3-7.4). It prints what it preserved, which sources it could associate and
 * which remain explicitly unresolved, and exits non-zero on any refusal.
 *
 * <pre>
 *   java -cp target/classes:&lt;dependencies&gt; infra.schema.LegacySchemaAdoptionCli \
 *       --backup=&lt;how the restorable backup can be found&gt; \
 *       --writers-stopped=&lt;who confirmed the old writers and schedules are stopped&gt; \
 *       --confirm [--schema=&lt;live schema&gt;] [--archive-schema=&lt;name&gt;]
 * </pre>
 *
 * <p>Database connection settings come from the normal environment configuration, never from
 * arguments, so no credential appears in a shell history or process listing.
 */
public final class LegacySchemaAdoptionCli {

    static final int EXIT_OK = 0;
    static final int EXIT_REFUSED = 1;
    static final int EXIT_USAGE = 2;

    private LegacySchemaAdoptionCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, LegacySchemaAdoption::adopt));
    }

    /**
     * The whole command, with its output streams and the adoption itself supplied, so argument
     * handling, reporting and exit codes are verifiable without running a real adoption.
     */
    static int run(String[] args,
                   PrintStream out,
                   PrintStream err,
                   Function<LegacySchemaAdoption.Request, LegacySchemaAdoption.Outcome> adopt) {
        String backup = null;
        String writersStopped = null;
        String liveSchema = null;
        String archiveSchema = LegacySchemaAdoption.DEFAULT_ARCHIVE_SCHEMA;
        boolean confirmed = false;

        for (String argument : args) {
            if (argument.startsWith("--backup=")) backup = value(argument);
            else if (argument.startsWith("--writers-stopped=")) writersStopped = value(argument);
            else if (argument.startsWith("--schema=")) liveSchema = value(argument);
            else if (argument.startsWith("--archive-schema=")) archiveSchema = value(argument);
            else if (argument.equals("--confirm")) confirmed = true;
            else {
                err.println("Unknown argument: " + argument);
                usage(err);
                return EXIT_USAGE;
            }
        }

        var request = new LegacySchemaAdoption.Request(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASS,
                liveSchema, archiveSchema, backup, writersStopped, confirmed);
        try {
            out.println(adopt.apply(request).report());
            return EXIT_OK;
        } catch (SchemaGateException refused) {
            err.println(refused.getMessage());
            if (refused.getCause() != null) err.println("Caused by: " + refused.getCause());
            usage(err);
            return EXIT_REFUSED;
        }
    }

    private static String value(String argument) {
        return argument.substring(argument.indexOf('=') + 1);
    }

    private static void usage(PrintStream err) {
        err.println("""

                Usage: LegacySchemaAdoptionCli --backup=<reference> --writers-stopped=<who> --confirm
                                               [--schema=<live schema>] [--archive-schema=<name>]

                Before running it: stop the backend, the JavaFX application and every scheduled job,
                then take a restorable database backup and name it in --backup. Nothing is deleted at
                any point, but backup/restore with a compatible application version is the only
                supported recovery boundary for this cutover.""");
    }
}
