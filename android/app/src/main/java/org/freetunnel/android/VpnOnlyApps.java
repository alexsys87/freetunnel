package org.freetunnel.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The applications that go only through the VPN, the last list of the Split page: the app's own, not a split profile's, since
 * what they are kept from is the internet without the VPN, whichever config and profile it runs. While a session runs they are
 * always inside it: never left out of it by Applications — bypass VPN, and added to Applications — via VPN where that list
 * is what the VPN carries (FreeTunnelVpnService.createTun). While the VPN is off, the service holds a VPN of its own that carries
 * only them and goes nowhere, so they have no internet (FreeTunnelVpnService.lockApps).
 */
final class VpnOnlyApps {
    static final String KEY = "vpn_only_apps";

    private VpnOnlyApps() { }

    /** The packages, in order. */
    static List<String> load(SharedPreferences prefs) {
        Set<String> saved = prefs.getStringSet(KEY, null);
        return saved == null ? Collections.emptyList() : new ArrayList<>(new TreeSet<>(saved));
    }

    static boolean save(SharedPreferences prefs, Collection<String> packages) {
        return packages.isEmpty() ? prefs.edit().remove(KEY).commit() : prefs.edit().putStringSet(KEY, new TreeSet<>(packages)).commit();
    }

    /** Those of them that are installed, this app left out: an application that is gone stays in the list, where it can be removed. */
    static List<String> installed(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("freetunnel", Context.MODE_PRIVATE);
        PackageManager pm = context.getPackageManager();
        List<String> found = new ArrayList<>();
        for (String pkg : load(prefs)) {
            if (pkg.equals(context.getPackageName())) continue;
            try { pm.getApplicationInfo(pkg, 0); found.add(pkg); }
            catch (PackageManager.NameNotFoundException gone) { /* left out */ }
        }
        return found;
    }

    /**
     * The applications Android's per-app rules get, with [vpnOnly] taken into account: where [allow] is true the list is what
     * the VPN carries, and they are added to it; otherwise it is what the VPN leaves out, and they are taken off it.
     */
    static List<String> apply(List<String> apps, boolean allow, Collection<String> vpnOnly) {
        TreeSet<String> result = new TreeSet<>(apps);
        if (allow) result.addAll(vpnOnly); else result.removeAll(vpnOnly);
        return new ArrayList<>(result);
    }
}
