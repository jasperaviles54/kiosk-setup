package com.laguardia.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Config-driven kiosk HOME launcher. The list of allowed apps and the admin
 * PINs are stored on-device (SharedPreferences) and editable from the in-app
 * Admin Settings screen (master PIN) -- no laptop or rebuild required to change.
 */
public class KioskActivity extends Activity {

    static final String PREFS = "kiosk_cfg";
    static final String KEY_APPS = "apps";
    static final String KEY_ADMIN_PIN = "admin_pin";
    static final String KEY_MASTER_PIN = "master_pin";

    static final String DEFAULT_APPS =
            "com.viber.voip,com.facebook.orca,com.google.android.gm,com.zui.camera,"
            + "com.google.android.apps.photos,com.google.android.apps.nbu.files";
    static final String DEFAULT_ADMIN_PIN = "246810";
    static final String DEFAULT_MASTER_PIN = "911911";

    // Always permitted under lock task: in-app photo/file pickers, Settings (for the
    // admin panel), and Google sign-in components (for the employee Gmail account).
    static final String[] SUPPORT_PACKAGES = new String[]{
            "com.zui.camera.qr",
            "com.google.android.documentsui",
            "com.google.android.photopicker",
            "com.google.android.providers.media.module",
            "com.android.intentresolver",
            "com.android.settings",
            "com.google.android.gms",
            "com.google.android.gsf"
    };

    // When true, the kiosk is paused for servicing (lock task off). Resets on reboot
    // (it's an in-memory flag), so the device always returns to the locked kiosk.
    static boolean sMaintenance = false;

    private DevicePolicyManager dpm;
    private ComponentName admin;
    private int titleTaps = 0;
    private long lastTap = 0L;

    static List<String> getApps(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        String csv = p.getString(KEY_APPS, DEFAULT_APPS);
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) { s = s.trim(); if (!s.isEmpty()) out.add(s); }
        return out;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, AdminReceiver.class);
        if (dpm.isDeviceOwnerApp(getPackageName())) {
            applyPolicies();
        }
        buildUi();
    }

    /** Rebuilds the lock-task allowlist from the current app config (+ self + support apps). */
    static void applyLockTaskPackages(DevicePolicyManager dpm, ComponentName admin, Context c) {
        LinkedHashSet<String> allow = new LinkedHashSet<>();
        allow.add(c.getPackageName());
        allow.addAll(getApps(c));
        for (String s : SUPPORT_PACKAGES) allow.add(s);
        try { dpm.setLockTaskPackages(admin, allow.toArray(new String[0])); } catch (Exception ignored) {}
    }

    void applyPolicies() {
        applyLockTaskPackages(dpm, admin, this);

        try {
            dpm.setLockTaskFeatures(admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                            | DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                            | DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                            | DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS);
        } catch (Exception ignored) {}

        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            dpm.addPersistentPreferredActivity(admin, home,
                    new ComponentName(this, KioskActivity.class));
        } catch (Exception ignored) {}

        // Tamper-proofing. NOT setting DISALLOW_INSTALL_APPS (stays serviceable) and NOT
        // DISALLOW_MODIFY_ACCOUNTS (the employee Gmail must be addable; account locking is
        // done per-type via the Admin panel's "Lock to current account").
        String[] restrictions = new String[]{
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_APPS_CONTROL,
                UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA
        };
        for (String key : restrictions) {
            try { dpm.addUserRestriction(admin, key); } catch (Exception ignored) {}
        }
        try {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7");
        } catch (Exception ignored) {}
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0F1B3D"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(60, 70, 60, 70);

        TextView title = new TextView(this);
        title.setText("Company Tablet");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 50);
        title.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onTitleTap(); }
        });
        root.addView(title);

        PackageManager pm = getPackageManager();
        for (String pkg : getApps(this)) {
            if (!isInstalled(pm, pkg)) continue;   // skip apps not on this device
            root.addView(makeButton(labelFor(pm, pkg), pkg));
        }
        setContentView(scroll);
        scroll.addView(root);
    }

    private String labelFor(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private Button makeButton(String label, final String pkg) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(720, 150);
        lp.setMargins(0, 16, 0, 16);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launch(pkg); }
        });
        return btn;
    }

    private void launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            startActivity(i);
        } else {
            Toast.makeText(this, "Not available: " + pkg, Toast.LENGTH_SHORT).show();
        }
    }

    private void onTitleTap() {
        long now = System.currentTimeMillis();
        if (now - lastTap > 2000) titleTaps = 0;
        lastTap = now;
        titleTaps++;
        if (titleTaps >= 7) {
            titleTaps = 0;
            promptAdminPin();
        }
    }

    private void promptAdminPin() {
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String adminPin = p.getString(KEY_ADMIN_PIN, DEFAULT_ADMIN_PIN);
        final String masterPin = p.getString(KEY_MASTER_PIN, DEFAULT_MASTER_PIN);
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Admin PIN")
                .setView(input)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String pin = input.getText().toString();
                        if (masterPin.equals(pin)) {
                            startActivity(new Intent(KioskActivity.this, AdminActivity.class));
                        } else if (adminPin.equals(pin)) {
                            adminExit();
                        } else {
                            Toast.makeText(KioskActivity.this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void adminExit() {
        try { stopLockTask(); } catch (Exception ignored) {}
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuild from current config (reflects changes made in Admin Settings).
        buildUi();
        if (dpm.isDeviceOwnerApp(getPackageName())) {
            applyLockTaskPackages(dpm, admin, this); // newly-added apps become launchable
            if (sMaintenance) {
                try { stopLockTask(); } catch (Exception ignored) {}   // stay unlocked for servicing
            } else {
                try {
                    if (dpm.isLockTaskPermitted(getPackageName())) {
                        startLockTask();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: swallow Back so there is no way out of the kiosk.
    }
}
