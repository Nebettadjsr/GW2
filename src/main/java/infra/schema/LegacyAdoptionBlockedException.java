package infra.schema;

import java.util.List;

/**
 * A preflight refusal: the adoption was rejected before anything was written, so the legacy database
 * is exactly as it was (TARGET_ARCHITECTURE.md 7.4, acceptance criterion 3).
 *
 * <p>Carries the individual reasons so an operator can repair or extend the supported inventory
 * under review rather than guessing what differed.
 */
public class LegacyAdoptionBlockedException extends SchemaGateException {

    private final List<String> reasons;

    public LegacyAdoptionBlockedException(String message, List<String> reasons) {
        super(message + System.lineSeparator() + "  - " + String.join(System.lineSeparator() + "  - ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> reasons() {
        return reasons;
    }
}
