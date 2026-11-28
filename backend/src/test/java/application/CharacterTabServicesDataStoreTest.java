package application;

import domain.access.AccountGrant;
import model.AccountScope;
import org.junit.jupiter.api.Test;
import application.access.AccountContext;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The three character tab services report an unreadable database as {@link DataStoreException} with the
 * original database exception as cause, so their callers never see a JDBC type.
 */
class CharacterTabServicesDataStoreTest {
    private static final AccountScope SCOPE = AccountScope.of("10000000-0000-0000-0000-000000000001");
    private static final Clock CLOCK = Clock.systemUTC();
    private static final Duration MAX_AGE = Duration.ofMinutes(15);
    private static final SQLException DOWN = new SQLException("down");
    private static final CharacterRosterService.ConnectionSource FAILING = () -> { throw DOWN; };
    private static final AccountContext CONTEXT = new AccountContext(SCOPE.id().toString(),
            EnumSet.allOf(AccountGrant.class), Set.of(), "test", Instant.now().plus(Duration.ofHours(1)));

    private static void assertWrapped(DataStoreException e) {
        assertSame(DOWN, e.getCause());
        assertInstanceOf(SQLException.class, e.getCause());
    }

    @Test
    void inventoryLayoutWrapsTheDatabaseFailure() {
        var service = new CharacterInventoryLayoutService(null, new repo.CharacterInventoryLayoutRepository(),
                FAILING, CLOCK, MAX_AGE);
        assertWrapped(assertThrows(DataStoreException.class, () -> service.read(CONTEXT, SCOPE, "Aria")));
    }

    @Test
    void equipmentTabsWrapTheDatabaseFailure() {
        var service = new CharacterEquipmentTabsService(null, new repo.CharacterEquipmentTabsRepository(),
                FAILING, CLOCK, MAX_AGE);
        assertWrapped(assertThrows(DataStoreException.class, () -> service.read(CONTEXT, SCOPE, "Aria")));
    }

    @Test
    void buildTabsWrapTheDatabaseFailure() {
        var service = new CharacterBuildTabsService(null, new repo.CharacterBuildTabsRepository(),
                FAILING, CLOCK, MAX_AGE);
        assertWrapped(assertThrows(DataStoreException.class, () -> service.read(CONTEXT, SCOPE, "Aria")));
    }
}
