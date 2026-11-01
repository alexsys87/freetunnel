package org.freetunnel.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The geo lists on the device (GeoStore), with a fake server: the lists a mode needs are downloaded, kept, checked again
// without being downloaded again while their published SHA-256 says they are unchanged, and a bad download never takes the
// place of a good list. The same cases as the desktop's tests/test_geolistupdater.cpp.
public final class GeoStoreTest {
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    private static final String BASE = "https://lists.test";

    private final Map<String, byte[]> served = new HashMap<>();
    private final Map<String, Integer> requests = new HashMap<>();
    private long now = 1_800_000_000_000L;
    private GeoStore store;

    private final GeoStore.Fetcher fetcher = (url, max) -> {
        requests.merge(url.substring(BASE.length()), 1, Integer::sum);
        byte[] body = served.get(url.substring(BASE.length()));
        if (body == null) throw new FileNotFoundException("HTTP 404");
        if (body.length > max) throw new IOException("too large");
        return body;
    };
    private final GeoStore.Clock clock = () -> now;

    // ---------- protobuf, the writing half ----------

    private static byte[] varint(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do { int b = (int) (v & 0x7f); v >>>= 7; if (v != 0) b |= 0x80; out.write(b); } while (v != 0);
        return out.toByteArray();
    }
    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
    private static byte[] lenField(int field, byte[] body) { return cat(varint(((long) field << 3) | 2), varint(body.length), body); }
    private static byte[] intField(int field, long v) { return cat(varint((long) field << 3), varint(v)); }
    // A geoip.dat category of IPv4 subnets: {a, b, c, d, prefix} each.
    private static byte[] geoip(String code, int[]... subnets) {
        byte[] body = lenField(1, code.getBytes(StandardCharsets.UTF_8));
        for (int[] s : subnets)
            body = cat(body, lenField(2, cat(lenField(1, new byte[] {(byte) s[0], (byte) s[1], (byte) s[2], (byte) s[3]}), intField(2, s[4]))));
        return lenField(1, body);
    }
    private static byte[] sha256sum(byte[] data, String name) {
        return (GeoStore.sha256(data) + "  " + name + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static Map<String, String> sources() {
        Map<String, String> s = new HashMap<>();
        s.put("blocked_sites", BASE + "/ru-blocked.txt");
        s.put("blocked_ips", BASE + "/geoip.dat#ru-blocked");
        s.put("russia_sites", BASE + "/ru-blocked.txt");
        s.put("russia_ips", BASE + "/geoip.dat#ru");
        return s;
    }

    @Before public void serve() throws IOException {
        store = new GeoStore(tmp.newFolder("geo"));
        byte[] geoip = cat(geoip("RU-BLOCKED", new int[] {203, 0, 113, 0, 24}),
                geoip("RU", new int[] {5, 3, 0, 0, 16}, new int[] {0, 0, 0, 0, 1}));
        served.put("/ru-blocked.txt", "domain:blocked.example\nfull:exact.example\n".getBytes(StandardCharsets.UTF_8));
        served.put("/geoip.dat", geoip);
        served.put("/geoip.dat.sha256sum", sha256sum(geoip, "geoip.dat"));
    }

    private int count(String path) { return requests.getOrDefault(path, 0); }

    private String update(GeoRouting.Mode mode, boolean force) {
        List<String> due = store.due(mode, sources(), true, force, now);
        return store.update(mode, sources(), due, fetcher, clock);
    }

    @Test public void theModesListsAreDownloadedAndBuilt() {
        assertNull(store.entries(GeoRouting.Mode.BLOCKED, sources()));
        assertEquals(2, store.due(GeoRouting.Mode.BLOCKED, sources(), true, false, now).size());
        assertEquals("", update(GeoRouting.Mode.BLOCKED, false));
        List<String> entries = GeoLists.lines(store.entries(GeoRouting.Mode.BLOCKED, sources()));
        assertTrue(entries.contains("blocked.example"));
        assertTrue(entries.contains("*.blocked.example"));
        assertTrue(entries.contains("exact.example"));
        assertTrue(entries.contains("203.0.113.0/24"));
        // Only the mode's own category of the shared file.
        assertFalse(entries.contains("5.3.0.0/16"));
        GeoStore.Status status = store.status(GeoRouting.Mode.BLOCKED, sources());
        assertTrue(status.ready);
        assertEquals(2, status.sites);
        assertEquals(1, status.addresses);
        assertEquals(now, status.lastChecked);
        assertEquals("", status.lastError);
        assertEquals(1, count("/geoip.dat"));
        // Checked within the day: nothing is due.
        assertTrue(store.due(GeoRouting.Mode.BLOCKED, sources(), true, false, now + 1000).isEmpty());
        assertEquals(now + GeoStore.CHECK_INTERVAL_MS, store.nextDue(GeoRouting.Mode.BLOCKED, sources(), true, now));
        assertEquals(-1, store.nextDue(GeoRouting.Mode.BLOCKED, sources(), false, now));
    }

    @Test public void aCachedListIsUsedWithoutTheNetwork() throws IOException {
        update(GeoRouting.Mode.BLOCKED, false);
        served.clear();
        GeoStore again = new GeoStore(store.cachePath("x").getParentFile());
        assertTrue(again.entries(GeoRouting.Mode.BLOCKED, sources()).contains("203.0.113.0/24"));
        assertTrue(again.due(GeoRouting.Mode.BLOCKED, sources(), true, false, now).isEmpty());
    }

    @Test public void anUnchangedListIsNotDownloadedAgain() {
        assertEquals("", store.version(GeoRouting.Mode.BLOCKED, sources()));
        update(GeoRouting.Mode.BLOCKED, false);
        String version = store.version(GeoRouting.Mode.BLOCKED, sources());
        assertEquals(64, version.length());
        now += 1000;
        assertEquals("", update(GeoRouting.Mode.BLOCKED, true));
        assertEquals(2, count("/geoip.dat.sha256sum"));
        // Its published SHA-256 is the cached copy's, so the file is not fetched again.
        assertEquals(1, count("/geoip.dat"));
        // The text list has no SHA-256 beside it, so it is.
        assertEquals(2, count("/ru-blocked.txt"));
        assertEquals(now, store.status(GeoRouting.Mode.BLOCKED, sources()).lastChecked);
        // The same entries: a running session has nothing to take in.
        assertEquals(version, store.version(GeoRouting.Mode.BLOCKED, sources()));
        // A new edition of the list: it has.
        served.put("/ru-blocked.txt", "domain:blocked.example\ndomain:new.example\n".getBytes(StandardCharsets.UTF_8));
        update(GeoRouting.Mode.BLOCKED, true);
        assertFalse(version.equals(store.version(GeoRouting.Mode.BLOCKED, sources())));
    }

    @Test public void aDayLaterTheListsAreCheckedAgain() {
        update(GeoRouting.Mode.BLOCKED, false);
        now += GeoStore.CHECK_INTERVAL_MS;
        assertEquals(2, store.due(GeoRouting.Mode.BLOCKED, sources(), true, false, now).size());
        // Not with the daily check off: the lists stay as they are.
        assertTrue(store.due(GeoRouting.Mode.BLOCKED, sources(), false, false, now).isEmpty());
    }

    @Test public void aDownloadThatDoesNotMatchItsChecksumIsRefused() {
        update(GeoRouting.Mode.BLOCKED, false);
        // A new edition announced, and a different file served.
        served.put("/geoip.dat.sha256sum", sha256sum(geoip("RU-BLOCKED", new int[] {198, 51, 100, 0, 24}), "geoip.dat"));
        served.put("/geoip.dat", geoip("RU-BLOCKED", new int[] {192, 0, 2, 0, 24}));
        String error = update(GeoRouting.Mode.BLOCKED, true);
        assertTrue(error, error.contains("SHA-256"));
        String entries = store.entries(GeoRouting.Mode.BLOCKED, sources());
        assertTrue(entries.contains("203.0.113.0/24"));
        assertFalse(entries.contains("192.0.2.0/24"));
        assertTrue(store.status(GeoRouting.Mode.BLOCKED, sources()).lastError.contains("SHA-256"));
    }

    @Test public void aBrokenDownloadKeepsTheGoodList() {
        update(GeoRouting.Mode.BLOCKED, false);
        // An error page served with 200, as some proxies do.
        served.put("/ru-blocked.txt", "<html>keyword:x</html>".getBytes(StandardCharsets.UTF_8));
        assertFalse(update(GeoRouting.Mode.BLOCKED, true).isEmpty());
        assertTrue(store.entries(GeoRouting.Mode.BLOCKED, sources()).contains("*.blocked.example"));
        // And a server that is gone keeps it too, and is tried again within the hour, not at once.
        served.remove("/ru-blocked.txt");
        assertFalse(update(GeoRouting.Mode.BLOCKED, true).isEmpty());
        assertTrue(store.entries(GeoRouting.Mode.BLOCKED, sources()).contains("*.blocked.example"));
        assertEquals(now + GeoStore.RETRY_INTERVAL_MS, store.nextDue(GeoRouting.Mode.BLOCKED, sources(), true, now));
    }

    // A list never downloaded is tried again an hour after a failed attempt, not on every start of the app.
    @Test public void aFailedFirstDownloadIsTriedAgainWithinTheHour() {
        served.remove("/ru-blocked.txt");
        assertFalse(update(GeoRouting.Mode.BLOCKED, false).isEmpty());
        // The other list of the mode routes meanwhile.
        assertTrue(store.status(GeoRouting.Mode.BLOCKED, sources()).ready);
        assertTrue(store.due(GeoRouting.Mode.BLOCKED, sources(), true, false, now + 60_000).isEmpty());
        now += GeoStore.RETRY_INTERVAL_MS;
        assertEquals(Collections.singletonList(BASE + "/ru-blocked.txt"), store.due(GeoRouting.Mode.BLOCKED, sources(), true, false, now));
    }

    @Test public void offFetchesNothing() {
        assertTrue(store.due(GeoRouting.Mode.OFF, sources(), true, true, now).isEmpty());
        assertNull(store.entries(GeoRouting.Mode.OFF, sources()));
        assertEquals(-1, store.nextDue(GeoRouting.Mode.OFF, sources(), true, now));
        assertTrue(requests.isEmpty());
    }

    // The Russian lists take traffic out of the tunnel, so a subnet that would take half the internet with it is left out.
    @Test public void theRussianListsLeaveOutWhatIsTooWideToBypass() {
        update(GeoRouting.Mode.EXCEPT_RUSSIA, false);
        String entries = store.entries(GeoRouting.Mode.EXCEPT_RUSSIA, sources());
        assertTrue(entries.contains("5.3.0.0/16"));
        assertFalse(entries.contains("0.0.0.0/1"));
        assertFalse(entries.contains("203.0.113.0/24"));
    }

    // A source changed by the user is read at once from what is cached, and the entries of the old one stop routing.
    @Test public void aChangedSourceRebuildsTheEntries() {
        update(GeoRouting.Mode.BLOCKED, false);
        Map<String, String> changed = sources();
        changed.put("blocked_ips", BASE + "/geoip.dat#ru");
        String entries = store.entries(GeoRouting.Mode.BLOCKED, changed);
        assertTrue(entries.contains("5.3.0.0/16"));
        assertFalse(entries.contains("203.0.113.0/24"));
        // A file never downloaded is due, and what is cached of the mode still routes until then.
        changed.put("blocked_sites", BASE + "/other.txt");
        assertEquals(Collections.singletonList(BASE + "/other.txt"), store.due(GeoRouting.Mode.BLOCKED, changed, true, false, now));
        assertTrue(store.entries(GeoRouting.Mode.BLOCKED, changed).contains("5.3.0.0/16"));
    }
}
