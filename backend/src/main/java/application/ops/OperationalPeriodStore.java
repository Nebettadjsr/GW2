package application.ops;

import java.io.InputStream;
import java.util.List;

/** Transport-neutral, bounded read seam over the retained activity periods. Authorization lives in the consumer. */
public interface OperationalPeriodStore {
    /** Periods plus availability, so an unreadable store is never shown as a healthy idle one. */
    record Periods(boolean available, List<PeriodDescriptor> items) {
        public static Periods unavailable() { return new Periods(false, List.of()); }
    }

    default Periods periods() { return new Periods(true, listPeriods()); }

    /** Retained periods, newest {@code fromUtc} first; a week sorts before the day that starts it. */
    List<PeriodDescriptor> listPeriods();

    /** Resolves an opaque selection inside the store; an unknown or expired id never substitutes another period. */
    Download openDownload(String selectionId);

    enum Status { OK, NOT_FOUND, UNAVAILABLE }

    /** A bounded streaming result; the caller closes it. Header and rows only, in chronological order. */
    record Download(Status status, PeriodDescriptor descriptor, InputStream stream) {
        public static Download notFound() { return new Download(Status.NOT_FOUND, null, null); }

        public static Download unavailable() { return new Download(Status.UNAVAILABLE, null, null); }
    }

    OperationalPeriodStore EMPTY = new OperationalPeriodStore() {
        @Override public List<PeriodDescriptor> listPeriods() { return List.of(); }

        @Override public Download openDownload(String selectionId) { return Download.notFound(); }
    };
}
