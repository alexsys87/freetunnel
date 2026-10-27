package org.freetunnel.android;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The app's preferences, as SplitStore reaches them. */
final class SplitPrefs implements SplitStore.Backing {
    private final SharedPreferences prefs;

    SplitPrefs(SharedPreferences prefs) { this.prefs = prefs; }

    @Override public boolean contains(String key) { return prefs.contains(key); }

    @Override public String getString(String key) { return prefs.getString(key, null); }

    // A copy: a set the preferences hand out must not be changed.
    @Override public Set<String> getStringSet(String key) {
        Set<String> set = prefs.getStringSet(key, null);
        return set == null ? null : new HashSet<>(set);
    }

    @Override public boolean commit(Map<String, Object> writes) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, Object> write : writes.entrySet()) {
            Object value = write.getValue();
            if (value instanceof String) editor.putString(write.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(write.getKey(), (Boolean) value);
            else if (value instanceof Set) {
                Set<String> copy = new HashSet<>();
                for (Object item : (Set<?>) value) copy.add((String) item);
                editor.putStringSet(write.getKey(), copy);
            } else throw new IllegalArgumentException("Not a preference: " + write.getKey());
        }
        return editor.commit();
    }
}
