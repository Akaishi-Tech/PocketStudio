package com.hcgstudio.pocketstudio.prootplugin;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import java.io.IOException;

public final class SetupActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button action;
    private ProgressBar progress;
    private DebianImage image;
    private boolean busy;
    private static volatile String failure;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        image = new DebianImage(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        status = new TextView(this);
        layout.addView(status);
        progress = new ProgressBar(this);
        layout.addView(progress);
        action = new Button(this);
        layout.addView(action);
        setContentView(layout);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onPause() { handler.removeCallbacksAndMessages(null); super.onPause(); }

    private void refresh() {
        boolean running = busy || image.installing();
        progress.setVisibility(running ? View.VISIBLE : View.GONE);
        action.setEnabled(!running);
        if (running) {
            status.setText(R.string.setup_busy);
            handler.postDelayed(this::refresh, 500);
            return;
        }
        try {
            boolean ready = image.bundled().equals(image.active());
            int text = ready ? R.string.setup_ready : image.active().isEmpty() ? R.string.setup_prompt : R.string.setup_upgrade_text;
            status.setText(failure == null ? getString(text) : failure);
            action.setText(ready ? R.string.setup_close : image.active().isEmpty() ? R.string.setup_install : R.string.setup_upgrade);
            action.setOnClickListener(view -> { if (ready) finish(); else install(); });
        } catch (IOException e) {
            status.setText(e.getLocalizedMessage());
            action.setEnabled(false);
        }
    }

    private void install() {
        failure = null;
        busy = true;
        refresh();
        new Thread(() -> {
            try { image.install(); }
            catch (Exception e) { failure = e.toString(); }
            finally { runOnUiThread(() -> { busy = false; if (!isDestroyed() && hasWindowFocus()) refresh(); }); }
        }, "Install Debian image").start();
    }
}
