package com.termux.api.shizuku;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.List;

/** Supported managers visible in this Android user, independent of Binder state. */
public final class ShizukuManagers {
    public static final String PLUS = "af.shizuku.plus.api";
    public static final String STOCK = "moe.shizuku.privileged.api";
    private ShizukuManagers() {}

    public static List<String> installed(PackageManager packages) {
        return find(packages, true);
    }

    /** Presence includes disabled apps; discovery and launching require enabled apps. */
    public static List<String> present(PackageManager packages) {
        return find(packages, false);
    }

    private static List<String> find(PackageManager packages, boolean enabledOnly) {
        List<String> found = new ArrayList<>();
        for (String name : new String[]{PLUS, STOCK}) {
            try {
                ApplicationInfo info = packages.getApplicationInfo(name, 0);
                if (!enabledOnly || info.enabled) found.add(name);
            } catch (PackageManager.NameNotFoundException absent) { /* Not installed for this user. */ }
        }
        return found;
    }

    public static Intent launchIntent(PackageManager packages) {
        for (String name : installed(packages)) {
            Intent intent = packages.getLaunchIntentForPackage(name);
            if (intent != null) return intent;
        }
        return null;
    }
}
