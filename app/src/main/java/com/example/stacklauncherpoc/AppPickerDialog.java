package com.example.stacklauncherpoc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class AppPickerDialog {
    interface Callback {
        void onAppSelected(ComponentName componentName, CharSequence label);
    }

    static AlertDialog show(Activity activity, Callback callback) {
        PackageManager pm = activity.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);
        List<Entry> entries = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) {
                continue;
            }
            if (activity.getPackageName().equals(info.activityInfo.packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(pm);
            entries.add(new Entry(
                    new ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    label != null ? label : info.activityInfo.packageName,
                    info));
        }

        Collections.sort(entries, Comparator.comparing(
                e -> e.label.toString().toLowerCase(Locale.ROOT)));

        if (entries.isEmpty()) {
            return new AlertDialog.Builder(activity)
                    .setTitle("Choose app")
                    .setMessage("No launchable apps found")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }

        EntryAdapter adapter = new EntryAdapter(activity, pm, entries);
        return new AlertDialog.Builder(activity)
                .setTitle("Choose app for panel")
                .setAdapter(adapter, (dialog, which) -> {
                    Entry entry = entries.get(which);
                    callback.onAppSelected(entry.componentName, entry.label);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static final class Entry {
        final ComponentName componentName;
        final CharSequence label;
        final ResolveInfo resolveInfo;

        Entry(ComponentName componentName, CharSequence label, ResolveInfo resolveInfo) {
            this.componentName = componentName;
            this.label = label;
            this.resolveInfo = resolveInfo;
        }
    }

    private static final class EntryAdapter extends ArrayAdapter<Entry> {
        private final PackageManager packageManager;
        private final int padding;
        private final int iconSize;

        EntryAdapter(Activity activity, PackageManager packageManager, List<Entry> entries) {
            super(activity, android.R.layout.simple_list_item_1, entries);
            this.packageManager = packageManager;
            float density = activity.getResources().getDisplayMetrics().density;
            padding = (int) (12 * density);
            iconSize = (int) (40 * density);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView text;

            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                icon = (ImageView) row.getChildAt(0);
                text = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(padding, padding / 2, padding, padding / 2);

                icon = new ImageView(getContext());
                row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

                text = new TextView(getContext());
                text.setTextSize(16);
                text.setPadding(padding, 0, 0, 0);
                row.addView(text, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }

            Entry entry = getItem(position);
            if (entry != null) {
                // Load icons lazily instead of retaining every Drawable in the dialog model.
                icon.setImageDrawable(entry.resolveInfo.loadIcon(packageManager));
                text.setText(entry.label);
            }
            return row;
        }
    }

    private AppPickerDialog() {
    }
}
