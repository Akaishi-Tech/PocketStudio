package com.hcgstudio.pocketstudio.avfplugin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;

public final class AvfSetupActivity extends Activity {
    private static final String TERMINAL_ACTION = "android.virtualization.VM_TERMINAL";
    private final Handler main = new Handler(Looper.getMainLooper());
    private Work work;
    private LinearLayout column;
    private TextView status, steps, commandText;
    private Button stage, clear, copy, restore, pair;
    private EditText fingerprint;

    private static final class Work {
        volatile boolean running;
        volatile String status = "";
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.avf_setup);
        work = (Work) getLastNonConfigurationInstance();
        if (work == null) work = new Work();
        ScrollView scroll = new ScrollView(this);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column);
        setContentView(scroll);
        addText(getString(R.string.avf_help));
        stage = button(R.string.avf_stage, v ->
            new AlertDialog.Builder(this).setTitle(R.string.avf_confirm_title).setMessage(R.string.avf_confirm)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.avf_confirm_action,
                    (dialog, which) -> run(R.string.avf_running, app -> { AvfInstaller.stage(app); return ""; })).show());
        steps = addText(getString(R.string.avf_guest_steps));
        commandText = addText("");
        commandText.setTextIsSelectable(true);
        copy = button(R.string.avf_copy, v -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText(getTitle(), commandText.getText()));
        });
        restore = button(R.string.avf_restore, v ->
            new AlertDialog.Builder(this).setMessage(R.string.avf_restore_help)
                .setPositiveButton(R.string.avf_restore, (dialog, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.avf_restore),
                        prefs().getString(AvfInstaller.RESTORE, "")));
                }).setNegativeButton(android.R.string.cancel, null).show());
        button(R.string.avf_open, v -> {
            try { startActivity(new Intent(TERMINAL_ACTION).setPackage("com.android.virtualization.terminal")); }
            catch (ActivityNotFoundException unavailable) { work.status = getString(R.string.avf_not_enabled); refresh(); }
        });
        fingerprint = new EditText(this);
        fingerprint.setSingleLine(true);
        fingerprint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fingerprint.setHint(R.string.avf_fingerprint);
        fingerprint.setText(state == null ? prefs().getString(AvfSsh.PIN, "") : state.getString("fingerprint", ""));
        column.addView(fingerprint);
        pair = button(R.string.avf_pair, v -> {
            String pin = fingerprint.getText().toString().trim();
            run(R.string.avf_checking, app -> { AvfSsh.pair(app, pin); return app.getString(R.string.avf_paired); });
        });
        clear = button(R.string.avf_clear, v ->
            new AlertDialog.Builder(this).setMessage(R.string.avf_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok,
                    (dialog, which) -> run(R.string.avf_clear, app -> { AvfInstaller.clear(app); return ""; })).show());
        status = addText("");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        refresh();
    }

    @Override public Object onRetainNonConfigurationInstance() { return work; }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("fingerprint", fingerprint.getText().toString());
    }

    private SharedPreferences prefs() { return getSharedPreferences(AvfSsh.PREFS, 0); }

    private Button button(int label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(action);
        column.addView(button);
        return button;
    }

    private TextView addText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        column.addView(view);
        return view;
    }

    private interface Operation { String perform(Context app) throws Exception; }

    private void run(int message, Operation operation) {
        if (work.running) return;
        Work pending = work;
        pending.status = getString(message);
        pending.running = true;
        Context app = getApplicationContext();
        Thread worker = new Thread(() -> {
            try { pending.status = operation.perform(app); }
            catch (Exception e) { pending.status = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString(); }
            finally { pending.running = false; }
        }, "AVF setup");
        worker.start();
        refresh();
    }

    private final Runnable tick = this::refresh;
    private void refresh() {
        main.removeCallbacks(tick);
        boolean abiMatches = Arrays.asList(Build.SUPPORTED_ABIS).contains(BuildConfig.IMAGE_ABI);
        String cmd = prefs().getString(AvfInstaller.COMMAND, "");
        boolean staged = !cmd.isEmpty();
        boolean supportedInstaller = Build.VERSION.SDK_INT == 36;
        stage.setEnabled(abiMatches && supportedInstaller && !work.running);
        clear.setEnabled(!work.running);
        pair.setEnabled(abiMatches && !work.running);
        steps.setVisibility(staged ? View.VISIBLE : View.GONE);
        copy.setVisibility(staged ? View.VISIBLE : View.GONE);
        restore.setVisibility(staged ? View.VISIBLE : View.GONE);
        clear.setVisibility(staged ? View.VISIBLE : View.GONE);
        commandText.setVisibility(staged ? View.VISIBLE : View.GONE);
        commandText.setText(cmd);
        if (!abiMatches) status.setText(getString(R.string.avf_wrong_abi, BuildConfig.IMAGE_ABI));
        else if (!supportedInstaller && work.status.isEmpty()) status.setText(R.string.avf_image_version);
        else status.setText(work.status);
        if (work.running) main.postDelayed(tick, 350);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(tick);
        super.onDestroy();
    }
}
