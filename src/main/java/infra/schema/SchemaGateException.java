package infra.schema;

/**
 * A schema state the backend must not start on, or a migration that failed.
 *
 * <p>Deliberately unchecked and deliberately fatal: every runtime entry point runs
 * {@link SchemaGate#runStartupGate()} before Spring beans, schedulers, tasks or repositories exist,
 * so letting this escape is what stops the application serving against a schema it has not
 * validated (TARGET_ARCHITECTURE.md 7.3).
 */
public class SchemaGateException extends RuntimeException {

    public SchemaGateException(String message) {
        super(message);
    }

    public SchemaGateException(String message, Throwable cause) {
        super(message, cause);
    }
}
