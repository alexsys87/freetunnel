package org.freetunnel.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The geo lists on the device, as the desktop's GeoListUpdater keeps them: the files the modes read, downloaded into a
 * cache directory, checked again once a day without being downloaded again while the SHA-256 published beside them says
 * they are unchanged, and the core's entries built from them. A download that does not match its published SHA-256, or
 * that cannot be read as the list it should be, never takes the place of a good file.
 *
 * <p>The app (GeoUpdater) downloads, and the VPN service reads the entries when it builds a session ({@link #entries}).
 * Both are in one process, on different threads; everything that reads or writes the files holds one lock, and nothing
 * holds it during a download. Downloading goes through {@link Fetcher}, so that this is tested on a plain JVM
 * (GeoStoreTest).
 */
public final class GeoStore {
    /** How long a list is trusted before it is checked again, and how long after a failed check the next try is. */
    static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    static final long RETRY_INTERVAL_MS = 60L * 60 * 1000;
    /** A list file larger than this is refused: the largest published one, the full geosite.dat, is about 75 MB. */
    static final long MAX_LIST_BYTES = 128L * 1024 * 1024;
    static final long MAX_CHECKSUM_BYTES = 4096;

    private static final Object LOCK = new Object();
    private static final String STATE = "state.properties";
    private static final Pattern SHA256SUM = Pattern.compile("^\\s*([0-9a-fA-F]{64})(\\s|$)");

    /** Downloads a file; an answer other than success, or one larger than {@code maxBytes}, is an IOException. */
    interface Fetcher {
        byte[] get(String url, long maxBytes) throws IOException;
    }

    /** What the window shows of a mode's lists. */
    public static final class Status {
        /** Whether the mode has entries, which is what it routes by. */
        boolean ready;
        int sites;
        int addresses;
        /** The oldest successful check of the mode's lists, in milliseconds since the epoch; 0 when one never was. */
        long lastChecked;
        /** What went wrong in the last update, and in reading the lists since; empty when nothing did. */
        String lastError = "";
    }

    private final File dir;

    GeoStore(File dir) { this.dir = dir; }

    // ---------- which files ----------

    /** The files (their addresses, without a fragment) the mode's lists are read from, each once. */
    static List<String> files(GeoRouting.Mode mode, Map<String, String> sources) {
        Set<String> files = new LinkedHashSet<>();
        for (GeoRouting.Slot slot : GeoRouting.slots(mode)) {
            GeoLists.Source source = GeoLists.parseSource(GeoRouting.sourceFor(slot, sources));
            if (source != null) files.add(source.file);
        }
        return new ArrayList<>(files);
    }

    File cachePath(String fileUrl) { return new File(dir, sha256(fileUrl.getBytes(StandardCharsets.UTF_8)).substring(0, 32) + ".list"); }

    // ---------- the state: per file its SHA-256, when it last changed and when it was last checked or failed ----------

    private Properties load(String name) {
        Properties p = new Properties();
        File f = new File(dir, name);
        if (!f.isFile()) return p;
        try (InputStream in = new FileInputStream(f)) { p.load(in); } catch (IOException | IllegalArgumentException unreadable) { p.clear(); }
        return p;
    }

    private void store(String name, Properties p) {
        try {
            writeAtomically(new File(dir, name), encode(p));
        } catch (IOException ignored) {
            // The next check finds the files unchecked and checks them again.
        }
    }

    private static byte[] encode(Properties p) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.store(out, null);
        return out.toByteArray();
    }

    private static long number(Properties p, String key) {
        try { return Long.parseLong(p.getProperty(key, "0")); } catch (NumberFormatException bad) { return 0; }
    }

    private boolean cached(Properties state, String file) {
        return cachePath(file).isFile() && number(state, file + ".checked") > 0;
    }

    // ---------- when ----------

    /**
     * The files of the mode to check now: every one with {@code force}; otherwise one never downloaded, and with {@code
     * autoUpdate} one last checked a day or more ago, unless an attempt at it failed within the hour.
     */
    List<String> due(GeoRouting.Mode mode, Map<String, String> sources, boolean autoUpdate, boolean force, long now) {
        List<String> out = new ArrayList<>();
        if (mode == GeoRouting.Mode.OFF) return out;
        synchronized (LOCK) {
            Properties state = load(STATE);
            for (String file : files(mode, sources)) {
                long failed = number(state, file + ".failed");
                boolean failedRecently = failed > 0 && now - failed < RETRY_INTERVAL_MS && now >= failed;
                boolean missing = !cached(state, file);
                long checked = number(state, file + ".checked");
                boolean stale = autoUpdate && (now - checked >= CHECK_INTERVAL_MS || now < checked);
                if (force || ((missing || stale) && !failedRecently)) out.add(file);
            }
        }
        return out;
    }

    /**
     * When the next file of the mode is due, in milliseconds since the epoch: now for one never downloaded, an hour after a
     * failed attempt, and a day after the last good check while {@code autoUpdate} is on. -1 when none ever is.
     */
    long nextDue(GeoRouting.Mode mode, Map<String, String> sources, boolean autoUpdate, long now) {
        if (mode == GeoRouting.Mode.OFF) return -1;
        long next = -1;
        synchronized (LOCK) {
            Properties state = load(STATE);
            for (String file : files(mode, sources)) {
                long failed = number(state, file + ".failed");
                long due = -1;
                if (failed > 0) due = failed + RETRY_INTERVAL_MS;
                else if (!cached(state, file)) due = now;
                else if (autoUpdate) due = number(state, file + ".checked") + CHECK_INTERVAL_MS;
                if (due >= 0 && (next < 0 || due < next)) next = due;
            }
        }
        return next;
    }

    // ---------- updating ----------

    /**
     * Checks {@code files} (see {@link #due}), one at a time, and builds the mode's entries again. Returns what went wrong,
     * one line per file that could not be checked, or an empty string; a file that failed keeps its good copy.
     */
    String update(GeoRouting.Mode mode, Map<String, String> sources, List<String> files, Fetcher fetcher, Clock clock) {
        List<String> errors = new ArrayList<>();
        for (String file : files) {
            String error = check(mode, sources, file, fetcher, clock);
            synchronized (LOCK) {
                Properties state = load(STATE);
                if (error == null) state.remove(file + ".failed");
                else state.setProperty(file + ".failed", Long.toString(clock.now()));
                store(STATE, state);
            }
            if (error != null) errors.add(name(file) + ": " + error);
        }
        String joined = String.join("; ", errors);
        synchronized (LOCK) {
            Properties state = load(STATE);
            state.setProperty("error." + mode.id, joined);
            store(STATE, state);
            rebuildLocked(mode, sources);
        }
        return joined;
    }

    /** The time, for the tests to set. */
    interface Clock { long now(); }

    // One file. The SHA-256 published beside it, where there is one (runetfreedom's and v2fly's .dat files have it, as
    // <file>.sha256sum): unchanged since the last download means nothing to download, and a download that does not match it
    // is refused. A source without one is downloaded and read as it is. Null when the file was checked, the reason when not.
    private String check(GeoRouting.Mode mode, Map<String, String> sources, String file, Fetcher fetcher, Clock clock) {
        String expected = "";
        try {
            Matcher m = SHA256SUM.matcher(new String(fetcher.get(file + ".sha256sum", MAX_CHECKSUM_BYTES), StandardCharsets.ISO_8859_1));
            if (m.find()) expected = m.group(1).toLowerCase(Locale.ROOT);
        } catch (IOException none) {
            // No checksum published, or not reachable: the file itself says whether the server is.
        }
        synchronized (LOCK) {
            Properties state = load(STATE);
            if (!expected.isEmpty() && expected.equals(state.getProperty(file + ".sha256")) && cachePath(file).isFile()) {
                state.setProperty(file + ".checked", Long.toString(clock.now()));
                store(STATE, state);
                return null;
            }
        }
        byte[] data;
        try {
            data = fetcher.get(file, MAX_LIST_BYTES);
        } catch (IOException failed) {
            String why = failed.getMessage();
            return why == null || why.isEmpty() ? failed.getClass().getSimpleName() : why;
        }
        String sha = sha256(data);
        if (!expected.isEmpty() && !sha.equals(expected)) return "the download does not match its published SHA-256";

        // Read before it replaces the copy in the cache: an error page, a cut-off file or one without the category asked
        // for must not take the place of a good list. It is good when one of the lists that read it can use it.
        String error = null;
        boolean usable = false;
        for (GeoRouting.Slot slot : GeoRouting.SLOTS) {
            GeoLists.Source source = GeoLists.parseSource(GeoRouting.sourceFor(slot, sources));
            if (source == null || !source.file.equals(file)) continue;
            try {
                GeoLists.append(data, source, slot.kind, false, new ArrayList<String>());
                usable = true;
            } catch (GeoLists.Refused refused) {
                if (error == null) error = refused.getMessage();
            }
        }
        if (!usable) return error == null ? "no list reads this file any more" : error;
        synchronized (LOCK) {
            try {
                writeAtomically(cachePath(file), data);
            } catch (IOException failed) {
                return "the list could not be saved";
            }
            Properties state = load(STATE);
            long now = clock.now();
            if (!sha.equals(state.getProperty(file + ".sha256"))) state.setProperty(file + ".fetched", Long.toString(now));
            state.setProperty(file + ".sha256", sha);
            state.setProperty(file + ".checked", Long.toString(now));
            store(STATE, state);
        }
        return null;
    }

    private static String name(String file) {
        String path = file;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    // ---------- the entries ----------

    private static String builtKey(GeoRouting.Mode mode, Map<String, String> sources, Properties state) {
        StringBuilder key = new StringBuilder(mode.id);
        for (GeoRouting.Slot slot : GeoRouting.slots(mode)) {
            String source = GeoRouting.sourceFor(slot, sources);
            GeoLists.Source parsed = GeoLists.parseSource(source);
            key.append('\n').append(source).append(' ').append(parsed == null ? "" : state.getProperty(parsed.file + ".sha256", ""));
        }
        return key.toString();
    }

    /**
     * The core's entries for the mode (GeoLists.join's form), built from the files in the cache: null when there are none,
     * which is before the first download, or when no list of the mode could be read. Built again when the sources or the
     * files changed since they last were.
     */
    String entries(GeoRouting.Mode mode, Map<String, String> sources) {
        if (mode == GeoRouting.Mode.OFF) return null;
        synchronized (LOCK) {
            Properties built = load("built-" + mode.id + ".properties");
            if (!builtKey(mode, sources, load(STATE)).equals(built.getProperty("key"))) rebuildLocked(mode, sources);
            File f = new File(dir, "built-" + mode.id + ".txt");
            if (!f.isFile()) return null;
            try {
                String text = new String(read(f), StandardCharsets.UTF_8);
                return text.isEmpty() ? null : text;
            } catch (IOException unreadable) {
                return null;
            }
        }
    }

    /** What the window shows of the mode's lists, from what was last built. */
    Status status(GeoRouting.Mode mode, Map<String, String> sources) {
        Status s = new Status();
        if (mode == GeoRouting.Mode.OFF) return s;
        synchronized (LOCK) {
            Properties state = load(STATE);
            Properties built = load("built-" + mode.id + ".properties");
            if (!builtKey(mode, sources, state).equals(built.getProperty("key"))) {
                rebuildLocked(mode, sources);
                built = load("built-" + mode.id + ".properties");
            }
            s.ready = number(built, "sites") + number(built, "addresses") > 0;
            s.sites = (int) number(built, "sites");
            s.addresses = (int) number(built, "addresses");
            long oldest = 0;
            for (String file : files(mode, sources)) {
                long checked = number(state, file + ".checked");
                if (checked <= 0 || !cachePath(file).isFile()) { oldest = 0; break; }
                if (oldest == 0 || checked < oldest) oldest = checked;
            }
            s.lastChecked = oldest;
            String update = state.getProperty("error." + mode.id, "");
            String read = built.getProperty("error", "");
            s.lastError = update.isEmpty() || read.isEmpty() ? update + read : update + "; " + read;
        }
        return s;
    }

    // The entries for the mode, from the cached files. A list whose file is missing or cannot be read adds nothing; the
    // others still count.
    private void rebuildLocked(GeoRouting.Mode mode, Map<String, String> sources) {
        Properties state = load(STATE);
        List<String> entries = new ArrayList<>();
        int sites = 0, addresses = 0;
        List<String> errors = new ArrayList<>();
        Map<String, byte[]> read = new HashMap<>();
        for (GeoRouting.Slot slot : GeoRouting.slots(mode)) {
            GeoLists.Source source = GeoLists.parseSource(GeoRouting.sourceFor(slot, sources));
            if (source == null) continue;
            byte[] data = read.get(source.file);
            if (data == null) {
                File f = cachePath(source.file);
                if (!f.isFile()) continue; // not downloaded yet: the update will say if it fails
                try { data = read(f); } catch (IOException unreadable) { errors.add(slot.id + ": the list could not be read"); continue; }
                read.put(source.file, data);
            }
            try {
                GeoLists.Stats stats = GeoLists.append(data, source, slot.kind, mode.bypass(), entries);
                if (slot.kind == GeoLists.Kind.SITES) sites += stats.entries; else addresses += stats.entries;
            } catch (GeoLists.Refused refused) {
                errors.add(slot.id + ": " + refused.getMessage());
            }
        }
        byte[] joined = GeoLists.join(entries).getBytes(StandardCharsets.UTF_8);
        String sha = joined.length == 0 ? "" : sha256(joined);
        try {
            writeAtomically(new File(dir, "built-" + mode.id + ".txt"), joined);
        } catch (IOException failed) {
            sha = "";
            errors.add("the entries could not be saved");
            sites = 0;
            addresses = 0;
            new File(dir, "built-" + mode.id + ".txt").delete();
        }
        Properties built = new Properties();
        built.setProperty("key", builtKey(mode, sources, state));
        built.setProperty("sites", Integer.toString(sites));
        built.setProperty("addresses", Integer.toString(addresses));
        built.setProperty("error", String.join("; ", errors));
        built.setProperty("sha", sha);
        store("built-" + mode.id + ".properties", built);
    }

    /**
     * A fingerprint of the mode's entries: the same while they are, different once they change, empty while there are none.
     * Tells whether a running session has to be built again after an update.
     */
    String version(GeoRouting.Mode mode, Map<String, String> sources) {
        if (mode == GeoRouting.Mode.OFF) return "";
        synchronized (LOCK) {
            Properties built = load("built-" + mode.id + ".properties");
            if (!builtKey(mode, sources, load(STATE)).equals(built.getProperty("key"))) {
                rebuildLocked(mode, sources);
                built = load("built-" + mode.id + ".properties");
            }
            return built.getProperty("sha", "");
        }
    }

    // ---------- files ----------

    private void writeAtomically(File target, byte[] data) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache directory");
        File tmp = new File(dir, target.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("could not replace " + target.getName());
        }
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length(), MAX_LIST_BYTES));
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder s = new StringBuilder(64);
            for (byte b : d) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return s.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
