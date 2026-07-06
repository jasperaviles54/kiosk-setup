package com.laguardia.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device admin panel (opened from the kiosk via the master PIN).
 * Lets IT pick which apps appear, change the PINs, drop to Android Settings,
 * or fully remove management -- all without a computer.
 */
public class AdminActivity extends Activity {

    private DevicePolicyManager dpm;
    private ComponentName admin;
    private final LinkedHashMap<String, CheckBox> boxes = new LinkedHashMap<>();
    private EditText adminPinField;
    private EditText masterPinField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, AdminReceiver.class);

        SharedPreferences p = getSharedPreferences(KioskActivity.PREFS, MODE_PRIVATE);
        List<String> current = KioskActivity.getApps(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(50, 50, 50, 50);

        root.addView(header("Admin Settings", 26));
        root.addView(header("Apps shown in kiosk", 18));

        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        LinkedHashMap<String, String> pkgLabel = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (!pkgLabel.containsKey(pkg)) pkgLabel.put(pkg, ri.loadLabel(pm).toString());
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(pkgLabel.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, String>>() {
            @Override public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                return a.getValue().compareToIgnoreCase(b.getValue());
            }
        });
        for (Map.Entry<String, String> e : entries) {
            CheckBox cb = new CheckBox(this);
            cb.setText(e.getValue() + "  (" + e.getKey() + ")");
            cb.setChecked(current.contains(e.getKey()));
            boxes.put(e.getKey(), cb);
            root.addView(cb);
        }

        root.addView(header("PINs", 18));
        root.addView(label("Servicing PIN (exit to Settings)"));
        adminPinField = pinField(p.getString(KioskActivity.KEY_ADMIN_PIN, KioskActivity.DEFAULT_ADMIN_PIN));
        root.addView(adminPinField);
        root.addView(label("Master PIN (opens this panel / unlock)"));
        masterPinField = pinField(p.getString(KioskActivity.KEY_MASTER_PIN, KioskActivity.DEFAULT_MASTER_PIN));
        root.addView(masterPinField);

        root.addView(button("Save & Apply", new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        }));
        root.addView(button("Open Android Settings", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { stopLockTask(); } catch (Exception ignored) {}
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }));

        root.addView(header("Employee Google account", 18));
        root.addView(label("To SET or CHANGE it:  1) Unlock   2) add/remove the account   3) Lock."));
        root.addView(button("1. Unlock Google accounts (to add or change)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dpm.setAccountManagementDisabled(admin, "com.google", false); } catch (Exception ignored) {}
                try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS); } catch (Exception ignored) {}
                Toast.makeText(AdminActivity.this,
                        "Unlocked. Use '2. Add account' below, or 'Open Android Settings' > Passwords & accounts to remove the old one first.",
                        Toast.LENGTH_LONG).show();
            }
        }));
        root.addView(button("2. Add / sign in a Google account", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dpm.setAccountManagementDisabled(admin, "com.google", false); } catch (Exception ignored) {}
                try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS); } catch (Exception ignored) {}
                try { stopLockTask(); } catch (Exception ignored) {}
                Intent i = new Intent(Settings.ACTION_ADD_ACCOUNT)
                        .putExtra(Settings.EXTRA_ACCOUNT_TYPES, new String[]{"com.google"})
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); }
                catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            }
        }));
        root.addView(button("3. Lock to current Google account (block others)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dpm.setAccountManagementDisabled(admin, "com.google", true); } catch (Exception ignored) {}
                Toast.makeText(AdminActivity.this, "Locked: no other Google account can be added.", Toast.LENGTH_LONG).show();
            }
        }));

        root.addView(header("Maintenance", 18));
        root.addView(label("Temporarily pause the kiosk to clear app data / manage apps in Settings, then re-lock."));
        root.addView(button("Maintenance mode (pause kiosk, manage apps)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                KioskActivity.sMaintenance = true;
                try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL); } catch (Exception ignored) {}
                try { stopLockTask(); } catch (Exception ignored) {}
                Toast.makeText(AdminActivity.this,
                        "Maintenance ON: you can now clear data / manage apps in Settings. Tap 'Re-lock kiosk' (or reboot) when done.",
                        Toast.LENGTH_LONG).show();
                try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
            }
        }));
        root.addView(button("Re-lock kiosk (end maintenance)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                KioskActivity.sMaintenance = false;
                try { dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL); } catch (Exception ignored) {}
                startActivity(new Intent(AdminActivity.this, KioskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Toast.makeText(AdminActivity.this, "Kiosk re-locked.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }));

        root.addView(button("Back to Kiosk", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        root.addView(button("Remove Management (unlock device)", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmRemove(); }
        }));

        scroll.addView(root);
        setContentView(scroll);
    }

    private void save() {
        List<String> selected = new ArrayList<>();
        for (Map.Entry<String, CheckBox> e : boxes.entrySet()) {
            if (e.getValue().isChecked()) selected.add(e.getKey());
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Pick at least one app", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences.Editor ed = getSharedPreferences(KioskActivity.PREFS, MODE_PRIVATE).edit();
        ed.putString(KioskActivity.KEY_APPS, join(selected));
        String ap = adminPinField.getText().toString().trim();
        String mp = masterPinField.getText().toString().trim();
        if (ap.length() >= 4) ed.putString(KioskActivity.KEY_ADMIN_PIN, ap);
        if (mp.length() >= 4) ed.putString(KioskActivity.KEY_MASTER_PIN, mp);
        ed.apply();
        // Permit the newly-selected apps to run under lock task (fixes added apps not opening).
        KioskActivity.applyLockTaskPackages(dpm, admin, this);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish(); // KioskActivity.onResume reloads config and rebuilds buttons
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setTitle("Remove management?")
                .setMessage("This unlocks the device and removes the kiosk lock. Use only to reconfigure or retire the tablet.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { fullUnlock(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void fullUnlock() {
        try { stopLockTask(); } catch (Exception ignored) {}
        if (dpm.isDeviceOwnerApp(getPackageName())) {
            String[] all = new String[]{
                    UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT,
                    UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_MODIFY_ACCOUNTS,
                    UserManager.DISALLOW_APPS_CONTROL, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                    UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_UNINSTALL_APPS,
                    UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
            };
            for (String key : all) { try { dpm.clearUserRestriction(admin, key); } catch (Exception ignored) {} }
            // Stop forcing this app as the default Home launcher, so a normal launcher takes over.
            try { dpm.clearPackagePersistentPreferredActivities(admin, getPackageName()); } catch (Exception ignored) {}
            try { dpm.clearDeviceOwnerApp(getPackageName()); } catch (Exception ignored) {}
        }
        Toast.makeText(this, "Management removed. Press Home and choose your normal launcher (set it as default).", Toast.LENGTH_LONG).show();
        finish();
    }

    private TextView header(String t, int size) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(size); tv.setTextColor(Color.BLACK);
        tv.setPadding(0, 30, 0, 14);
        return tv;
    }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(13); tv.setTextColor(Color.DKGRAY);
        tv.setPadding(0, 12, 0, 2);
        return tv;
    }

    private EditText pinField(String value) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(value);
        return et;
    }

    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 18, 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) { if (i > 0) sb.append(','); sb.append(items.get(i)); }
        return sb.toString();
    }
}
