package application;

import application.access.AccountAccessService;
import application.access.AccountLifecycleGate;
import model.AccountScope;
import repo.AccountRegistryRepository;
import repo.Db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Deletes one verified account's cached data (STORY-DELETE-001, UD-016: the database is a disposable
 * cache, so there is no backup, archive, export or tombstone).
 *
 * <p>Order, all inside the account's exclusive lifecycle gate: refuse when refresh work is unfinished;
 * wait for already-admitted account operations to leave; one transaction takes the per-account writer
 * lock and deletes the registry root (everything account-owned cascades); only after commit are the
 * coordinator's state cleared and every session of the GUID deletion-invalidated. On any failure the
 * gate reopens and neither sessions nor coordinator state are touched.
 */
public final class AccountDataDeletionService {

    public enum Result { DELETED, WORK_IN_PROGRESS, UNAVAILABLE }

    /** The transactional deletion primitive; the default opens a real transaction. */
    @FunctionalInterface
    public interface Deleter {
        void delete(AccountScope scope) throws SQLException;
    }

    private final AccountAccessService access;
    private final ScopedAccountRefreshCoordinator coordinator;
    private final Deleter deleter;

    public AccountDataDeletionService(AccountAccessService access, ScopedAccountRefreshCoordinator coordinator) {
        this(access, coordinator, AccountDataDeletionService::deleteInTransaction);
    }

    public AccountDataDeletionService(AccountAccessService access, ScopedAccountRefreshCoordinator coordinator,
                                      Deleter deleter) {
        this.access = access;
        this.coordinator = coordinator;
        this.deleter = deleter;
    }

    /** @param ownAdmissions shared gate admissions held by the calling request itself */
    public Result delete(AccountScope scope, int ownAdmissions) {
        AccountLifecycleGate.DeletionAttempt attempt = access.lifecycle().beginDeletion(
                scope.accountId(), () -> coordinator.hasUnfinishedWork(scope), ownAdmissions);
        switch (attempt.start()) {
            case WORK_IN_PROGRESS -> { return Result.WORK_IN_PROGRESS; }
            case TIMED_OUT -> { return Result.UNAVAILABLE; }
            case ENTERED -> { /* proceed */ }
        }
        try (var ignored = attempt.exclusive()) {
            try {
                deleter.delete(scope);
            } catch (SQLException | RuntimeException failure) {
                return Result.UNAVAILABLE;
            }
            coordinator.forgetAccount(scope);
            access.invalidateAccountSessions(scope.id());
            return Result.DELETED;
        }
    }

    private static void deleteInTransaction(AccountScope scope) throws SQLException {
        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            try {
                new AccountRegistryRepository().deleteAccount(con, scope);
                con.commit();
            } catch (SQLException | RuntimeException failure) {
                try { con.rollback(); } catch (SQLException suppressed) { failure.addSuppressed(suppressed); }
                throw failure;
            }
        }
    }
}
