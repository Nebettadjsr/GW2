package web;

/** A state-changing request that did not come from this application's own origin. */
public class ForeignOriginException extends RuntimeException {
    public ForeignOriginException() {
        super("foreign origin", null, false, false);
    }
}
