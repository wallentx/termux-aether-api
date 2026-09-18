package com.termux.api.activities;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.api.R;

import rikka.shizuku.Shizuku;

/** Foreground-only, explicit consent. Never automatically grants Shizuku access. */
public final class ShizukuAccessActivity extends Activity {
    private static final int REQUEST_ACCESS = 41;
    private TextView status;
    private Button request;
    private boolean automaticRequestUsed;
    private final Shizuku.OnBinderReceivedListener received = () -> runOnUiThread(this::refresh);
    private final Shizuku.OnBinderDeadListener died = () -> runOnUiThread(this::refresh);
    private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> {
        if (code == REQUEST_ACCESS) runOnUiThread(this::refresh);
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.shizuku_access);
        automaticRequestUsed = state != null && state.getBoolean("automatic_request_used");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView explanation = new TextView(this);
        explanation.setText(R.string.shizuku_explanation);
        layout.addView(explanation);
        status = new TextView(this);
        status.setPadding(0, padding, 0, padding);
        layout.addView(status);
        request = new Button(this);
        request.setText(R.string.shizuku_request_access);
        request.setOnClickListener(view -> requestAccess());
        layout.addView(request);
        Button manager = new Button(this);
        manager.setText(R.string.shizuku_open_manager);
        manager.setOnClickListener(view -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) startActivity(launch);
            else status.setText(R.string.shizuku_install_manager);
        });
        layout.addView(manager);
        setContentView(layout);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(died);
        Shizuku.addRequestPermissionResultListener(permission);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        if (getIntent().getBooleanExtra("request_permission", false) && !automaticRequestUsed) {
            automaticRequestUsed = true;
            requestAccess();
        }
    }

    private void refresh() {
        if (isFinishing() || isDestroyed()) return;
        try {
            if (!Shizuku.pingBinder()) {
                status.setText(R.string.shizuku_not_running);
                request.setEnabled(false);
            } else if (Shizuku.isPreV11()) {
                status.setText(R.string.shizuku_too_old);
                request.setEnabled(false);
            } else {
                boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                status.setText(granted ? R.string.shizuku_access_granted : R.string.shizuku_access_needed);
                request.setEnabled(!granted);
            }
        } catch (RuntimeException error) {
            status.setText(R.string.shizuku_not_running);
            request.setEnabled(false);
        }
    }

    private void requestAccess() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                refresh();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refresh();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                status.setText(R.string.shizuku_denied_permanently);
            } else {
                Shizuku.requestPermission(REQUEST_ACCESS);
            }
        } catch (RuntimeException error) {
            refresh();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("automatic_request_used", automaticRequestUsed);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(died);
        Shizuku.removeRequestPermissionResultListener(permission);
        super.onDestroy();
    }
}
