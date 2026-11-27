package shortener;

import shortener.core.LinkService;
import java.net.URI;
import java.time.*;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkServiceTest {
    private static final String URL = "https://example.com/path";
    private final TestClock clock = new TestClock();
    private final AtomicInteger sequence = new AtomicInteger();
    private final LinkService links = service(Duration.ofHours(24));

    private LinkService service(Duration ttl) {
        return new LinkService(clock, ttl, Duration.ofDays(7), 100,
            () -> String.format(Locale.ROOT, "%012d", sequence.incrementAndGet()), LinkService.emptyState());
    }

    @Test void generatesDifferentCodesForDifferentUsers() {
        var first = links.create(null, URL, 2);
        var second = links.create(null, URL, 2);
        assertNotEquals(first.code(), second.code());
        assertNotEquals(first.owner(), second.owner());
    }

    @Test void keepsSeveralLinksForOneOwner() {
        var first = links.create(null, URL, 2);
        var second = links.create(first.owner(), URL, 3);
        assertEquals(2, links.links(first.owner()).size());
        assertNotEquals(first.code(), second.code());
    }

    @Test void retryUsesNewCodeEvenAfterOldNoticeIsPurged() {
        var first = links.create(null, URL, 1);
        links.visit(first.code());
        clock.advance(Duration.ofDays(8));
        links.sweep();
        assertTrue(links.snapshot().closed().isEmpty());
        sequence.set(0);
        assertNotEquals(first.code(), links.create(first.owner(), URL, 1).code());
    }

    @Test void rejectsUnsupportedAndMalformedDestinations() {
        for (String value : new String[]{"file:///tmp/foo", "example.com", "https://user@example.com", "http://a:99999", "http://x/y z"}) {
            assertThrows(IllegalArgumentException.class, () -> links.create(null, value, 1));
        }
        assertTrue(links.snapshot().users().isEmpty());
    }

    @Test void rejectsOutOfBoundsVisitLimits() {
        assertThrows(IllegalArgumentException.class, () -> links.create(null, URL, 0));
        assertThrows(IllegalArgumentException.class, () -> links.create(null, URL, 101));
    }

    @Test void deniesEditingAndRemovingOtherOwnersLinks() {
        var first = links.create(null, URL, 2);
        var second = links.create(null, URL, 2);
        assertThrows(IllegalArgumentException.class, () -> links.edit(second.owner(), first.code(), null, 3));
        assertThrows(IllegalArgumentException.class, () -> links.delete(second.owner(), first.code()));
        assertEquals(2, links.links(first.owner()).getFirst().limit());
    }

    @Test void permitsChangingTargetAndLimitWithoutChangingExpiration() {
        var original = links.create(null, URL, 2);
        var updated = links.edit(original.owner(), original.code(), "https://example.org/", 3);
        assertEquals(URI.create("https://example.org/"), updated.target());
        assertEquals(3, updated.limit());
        assertEquals(original.expires(), updated.expires());
    }

    @Test void preventsReducingLimitBelowOrToUsedVisits() {
        var link = links.create(null, URL, 3);
        links.visit(link.code());
        assertThrows(IllegalArgumentException.class, () -> links.edit(link.owner(), link.code(), null, 1));
        assertEquals(3, links.links(link.owner()).getFirst().limit());
    }

    @Test void allowsExactlyConfiguredNumberOfVisits() {
        var link = links.create(null, URL, 2);
        assertNotNull(links.visit(link.code()).target());
        assertEquals(1, links.links(link.owner()).getFirst().visits());
        assertNotNull(links.visit(link.code()).target());
        assertEquals(LinkService.Reason.LIMIT, links.visit(link.code()).reason());
        assertTrue(links.links(link.owner()).isEmpty());
    }

    @Test void simultaneousVisitsRespectLimit() throws Exception {
        var link = links.create(null, URL, 7);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(IntStream.range(0, 40)
                .<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> links.visit(link.code()).target() != null).toList());
            int successful = 0;
            for (var result : results) {
                if (result.get()) {
                    successful++;
                }
            }
            assertEquals(7, successful);
        }
    }

    @Test void expiresExactlyAtBoundaryForHourAndDay() {
        for (Duration ttl : new Duration[]{Duration.ofHours(1), Duration.ofDays(1)}) {
            var store = service(ttl);
            var link = store.create(null, URL, 3);
            clock.advance(ttl.minusMillis(1));
            assertNotNull(store.status(link.code()).target());
            clock.advance(Duration.ofMillis(1));
            store.sweep();
            assertTrue(store.snapshot().links().isEmpty());
            assertEquals(LinkService.Reason.EXPIRED, store.visit(link.code()).reason());
        }
    }

    @Test void deliversPrivateNotificationOnce() {
        var ownerLink = links.create(null, URL, 1);
        var other = links.create(null, URL, 2);
        links.visit(ownerLink.code());
        assertTrue(links.notices(other.owner()).isEmpty());
        assertEquals(LinkService.Reason.LIMIT, links.notices(ownerLink.owner()).getFirst().reason());
        assertTrue(links.notices(ownerLink.owner()).isEmpty());
    }

    @Test void reportsExpirationInOwnerNotifications() {
        var link = links.create(null, URL, 5);
        clock.advance(Duration.ofDays(1));
        links.sweep();
        assertEquals(LinkService.Reason.EXPIRED, links.notices(link.owner()).getFirst().reason());
    }

    @Test void ownerCanDeleteLink() {
        var link = links.create(null, URL, 2);
        links.delete(link.owner(), link.code());
        assertEquals(LinkService.Reason.DELETED, links.visit(link.code()).reason());
        assertTrue(links.notices(link.owner()).isEmpty());
    }

    private static final class TestClock extends Clock {
        private Instant time = Instant.parse("2026-11-27T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(time, zone); }
        @Override public Instant instant() { return time; }
        void advance(Duration duration) { time = time.plus(duration); }
    }
}
