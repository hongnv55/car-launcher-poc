package com.example.stacklauncherpoc;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/** Remembers which app each panel hosts. */
final class AppSelectionStore {
    private static final String PREFS = "stack_launcher";
    private static final String KEY_COMPONENT_PREFIX = "host_component_";

    private final SharedPreferences prefs;

    AppSelectionStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(int slot) {
        return KEY_COMPONENT_PREFIX + slot;
    }

    ComponentName load(int slot) {
        String value = prefs.getString(key(slot), null);
        return value == null ? null : ComponentName.unflattenFromString(value);
    }

    void save(int slot, ComponentName componentName) {
        prefs.edit().putString(key(slot), componentName.flattenToString()).apply();
    }

    void clear(int slot) {
        prefs.edit().remove(key(slot)).apply();
    }
}
