package domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

class CharacterBirthdayTest {
    private static final long Y = 365L * 24 * 60 * 60;
    private static final Instant C = Instant.parse("2020-02-29T12:00:00Z");

    @Test
    void justAfterCreation() {
        assertEquals(C.plusSeconds(Y), CharacterBirthday.next(C, C));
        assertEquals(C.plusSeconds(Y), CharacterBirthday.next(C, C.plusSeconds(1)));
    }

    @Test
    void boundaries() {
        assertEquals(C.plusSeconds(Y), CharacterBirthday.next(C, C.plusSeconds(Y - 1)));
        assertEquals(C.plusSeconds(2 * Y), CharacterBirthday.next(C, C.plusSeconds(Y)));
        assertEquals(C.plusSeconds(2 * Y), CharacterBirthday.next(C, C.plusSeconds(Y + 1)));
        assertEquals(C.plusSeconds(2 * Y), CharacterBirthday.next(C, C.plusSeconds(2 * Y - 1)));
    }

    @Test
    void manyIntervalsAndFixedDurationAroundLeapDay() {
        assertEquals(C.plusSeconds(6 * Y), CharacterBirthday.next(C, C.plusSeconds(5 * Y + 100)));
        // 2021-02-28T12:00Z is exactly 365 days after the leap day, not a calendar anniversary.
        assertEquals(Instant.parse("2021-02-28T12:00:00Z"), CharacterBirthday.next(C, C.plusSeconds(10)));
    }

    @Test
    void invariantUnderDefaultTimezone() {
        TimeZone old = TimeZone.getDefault();
        try {
            Instant expected = CharacterBirthday.next(C, Instant.parse("2023-03-12T08:00:00Z"));
            for (String z : new String[] {"UTC", "America/New_York", "Pacific/Auckland", "Asia/Kolkata"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(z));
                assertEquals(expected, CharacterBirthday.next(C, Instant.parse("2023-03-12T08:00:00Z")), z);
            }
        } finally {
            TimeZone.setDefault(old);
        }
    }

    @Test
    void nullAndFutureCreation() {
        assertNull(CharacterBirthday.next(null, C));
        assertNull(CharacterBirthday.next(C.plusSeconds(1), C));
    }

    @Test
    void overflowReturnsNullNeverWraps() {
        assertNull(CharacterBirthday.next(Instant.MAX.minusSeconds(10), Instant.MAX.minusSeconds(5)));
        assertNull(CharacterBirthday.next(Instant.MIN, Instant.MAX));
    }
}
