package org.freetunnel.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Geo routing, as on the desktop (src/core/GeoListUpdater.cpp): the Split page's choice between routing only what is
 * blocked in Russia through the tunnel, everything but Russian sites, or the user's own rules alone; the lists each mode
 * routes by, and where they come from. The lists are read by {@link GeoLists}, kept by {@link GeoStore} and downloaded
 * by GeoUpdater; RoutingConfig turns them into what the core is told.
 *
 * <p>The choice is in the app's preferences: {@link #MODE_KEY} holds a mode's name, and {@code geo_source_<slot>} a
 * source the user chose for a slot instead of its default.
 */
public final class GeoRouting {
    private GeoRouting() { }

    static final String MODE_KEY = "geo_mode";
    static final String AUTO_UPDATE_KEY = "geo_auto_update";
    static final String SOURCE_KEY_PREFIX = "geo_source_";

    /**
     * How traffic is split by geo lists. OFF: the user's own split-tunnelling rules alone, as before; BLOCKED: only what is
     * blocked in Russia goes through the tunnel; EXCEPT_RUSSIA: everything goes through the tunnel but Russian sites.
     */
    public enum Mode {
        OFF("off"), BLOCKED("blocked"), EXCEPT_RUSSIA("except_ru");

        final String id;
        Mode(String id) { this.id = id; }

        /** Anything unknown, or nothing, is OFF. */
        static Mode of(String id) {
            for (Mode m : values()) if (m.id.equals(id)) return m;
            return OFF;
        }

        /** Whether the mode's lists take what they name out of the tunnel (the core's general mode), not into it. */
        boolean bypass() { return this == EXCEPT_RUSSIA; }
    }

    /** One of the lists the modes use. */
    static final class Slot {
        final String id;
        final GeoLists.Kind kind;
        final Mode mode;
        final String defaultSource;
        Slot(String id, GeoLists.Kind kind, Mode mode, String defaultSource) {
            this.id = id; this.kind = kind; this.mode = mode; this.defaultSource = defaultSource;
        }
    }

    // The default sources, the desktop's. runetfreedom publishes Russia's block lists (from antifilter.download and
    // re:filter) as V2Ray lists several times a day, with a SHA-256 beside each .dat file; the domains also as a plain text
    // list of under 2 MB, which spares a daily download of the 75 MB geosite.dat they are part of. v2fly's
    // domain-list-community is the source of V2Ray's own category-ru, the sites of Russian companies and services; its
    // dlc.dat is a couple of megabytes.
    private static final String RUNET_GEOIP = "https://github.com/runetfreedom/russia-blocked-geoip/releases/latest/download/geoip.dat";

    static final List<Slot> SLOTS = Collections.unmodifiableList(Arrays.asList(
            new Slot("blocked_sites", GeoLists.Kind.SITES, Mode.BLOCKED,
                    "https://github.com/runetfreedom/russia-blocked-geosite/releases/latest/download/ru-blocked.txt"),
            new Slot("blocked_ips", GeoLists.Kind.IPS, Mode.BLOCKED, RUNET_GEOIP + "#ru-blocked"),
            new Slot("russia_sites", GeoLists.Kind.SITES, Mode.EXCEPT_RUSSIA,
                    "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat#category-ru"),
            new Slot("russia_ips", GeoLists.Kind.IPS, Mode.EXCEPT_RUSSIA, RUNET_GEOIP + "#ru")));

    static Slot slot(String id) {
        for (Slot s : SLOTS) if (s.id.equals(id)) return s;
        return null;
    }

    /** The slot of a mode for sites ({@code sites}) or for addresses; null for OFF. */
    static Slot slot(Mode mode, boolean sites) {
        for (Slot s : SLOTS) if (s.mode == mode && (s.kind == GeoLists.Kind.SITES) == sites) return s;
        return null;
    }

    /** The slots a mode routes by. */
    static List<Slot> slots(Mode mode) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : SLOTS) if (s.mode == mode) out.add(s);
        return out;
    }

    /** The source a slot reads: the user's choice in {@code chosen}, by slot id, or the slot's default. */
    static String sourceFor(Slot slot, Map<String, String> chosen) {
        String s = chosen == null ? null : chosen.get(slot.id);
        return s == null || s.trim().isEmpty() ? slot.defaultSource : s.trim();
    }
}
