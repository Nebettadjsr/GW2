package web;

import java.util.Set;

public final class AccountDataStaleException extends RuntimeException {
    private final String taskId;
    private final Set<String> sources;
    public AccountDataStaleException(String taskId, Set<String> sources) { this.taskId = taskId; this.sources = Set.copyOf(sources); }
    public String taskId() { return taskId; }
    public Set<String> sources() { return sources; }
}
