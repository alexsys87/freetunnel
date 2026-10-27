package org.freetunnel.android;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * "Recommended for Russia", the list the desktop and this app share: data/split/recommended-russia.txt, which the build
 * copies into the assets (the copySharedData task of app/build.gradle) and the desktop has compiled in
 * (cmake/SharedData.cmake). One rule to a line, in UTF-8; a blank line, and a line that begins with #, is no rule.
 */
final class RussiaList {
    static final String ASSET = "split/recommended-russia.txt";
    private static volatile List<String> cached;

    private RussiaList() { }

    /** The rules in a file's text, in its order. The same reading as the desktop's, line by line. */
    static List<String> parse(String text) {
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<String> rules = new ArrayList<>();
        for (String line : body.split("\n")) {
            String rule = line.trim();
            if (!rule.isEmpty() && rule.charAt(0) != '#') rules.add(rule);
        }
        return Collections.unmodifiableList(rules);
    }

    /** The list from the assets; it is read once. */
    static List<String> read(Context context) throws IOException {
        List<String> list = cached;
        if (list != null) return list;
        try (InputStream in = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) bytes.write(chunk, 0, n);
            list = parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        cached = list;
        return list;
    }

    /** The list for SplitStore, which wants one when it makes a new installation's Default; nothing, if the assets cannot be read. */
    static Supplier<List<String>> seed(Context context) {
        return () -> {
            try { return read(context); }
            catch (IOException unreadable) { return Collections.<String>emptyList(); }
        };
    }
}
