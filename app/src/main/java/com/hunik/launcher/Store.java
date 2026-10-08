package com.hunik.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Everything the launcher remembers: dock apps, home shortcuts, hidden apps. */
final class Store {
    private final SharedPreferences p;

    Store(Context c) {
        p = c.getSharedPreferences("hunik_launcher", Context.MODE_PRIVATE);
    }

    boolean hasDock() {
        return p.contains("dock");
    }

    String[] dock(int n) {
        String[] parts = p.getString("dock", "").split("\\|", -1);
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = i < parts.length ? parts[i] : "";
        return out;
    }

    void setDock(String[] d) {
        p.edit().putString("dock", TextUtils.join("|", d)).apply();
    }

    /** package -> cell index (row * COLS + col). */
    LinkedHashMap<String, Integer> home() {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<String, Integer>();
        String s = p.getString("home", "");
        if (s.length() == 0) return m;
        for (String e : s.split("\\|")) {
            int at = e.lastIndexOf('@');
            if (at <= 0) continue;
            try {
                m.put(e.substring(0, at), Integer.parseInt(e.substring(at + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    void setHome(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('|');
            sb.append(e.getKey()).append('@').append(e.getValue());
        }
        p.edit().putString("home", sb.toString()).apply();
    }

    Set<String> hidden() {
        return new HashSet<String>(p.getStringSet("hidden", new HashSet<String>()));
    }

    void setHidden(Set<String> s) {
        p.edit().putStringSet("hidden", s).apply();
    }
}
