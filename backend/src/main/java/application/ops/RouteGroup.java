package application.ops;

/** The fixed, compile-time route groups of the operational history (STORY-OPS-001); never dynamic labels. */
public enum RouteGroup {
    ACCOUNT_DATA(true), ACCOUNT_REFRESH(true), CRAFTING(true), CHARACTERS(true), INVENTORY(true),
    SHARED_GAME_DATA(false), TASK_CONTROL(false), OTHER_API(false);

    private final boolean foreground;

    RouteGroup(boolean foreground) { this.foreground = foreground; }

    /** True for the five groups whose authenticated requests count as foreground player activity. */
    public boolean foreground() { return foreground; }
}
