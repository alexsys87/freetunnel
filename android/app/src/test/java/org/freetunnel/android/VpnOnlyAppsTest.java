package org.freetunnel.android;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class VpnOnlyAppsTest {
    @Test public void theyAreAddedToAnAllowlist() {
        assertEquals(Arrays.asList("a", "b", "c"), VpnOnlyApps.apply(Arrays.asList("c", "a"), true, Collections.singletonList("b")));
    }

    @Test public void theyAreTakenOffWhatTheVpnLeavesOut() {
        assertEquals(Collections.singletonList("a"), VpnOnlyApps.apply(Arrays.asList("a", "b"), false, Arrays.asList("b", "x")));
    }

    @Test public void withNoneTheListIsAsItWas() {
        assertEquals(Arrays.asList("a", "b"), VpnOnlyApps.apply(Arrays.asList("b", "a"), false, Collections.emptyList()));
    }
}
