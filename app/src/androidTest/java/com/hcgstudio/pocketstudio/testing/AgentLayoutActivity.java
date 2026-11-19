package com.hcgstudio.pocketstudio.testing;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Deterministic foreground controls for ADB hierarchy and input tests. */
public final class AgentLayoutActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setFocusableInTouchMode(true);
        form.setFitsSystemWindows(true);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setContentDescription("Test input");
        field.setText("original");
        form.addView(field);
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText("Activate");
        button.setOnClickListener(view -> button.setText("Activated"));
        button.setOnLongClickListener(view -> {
            button.setText("Held");
            return true;
        });
        form.addView(button);
        ScrollView scroll = new ScrollView(this);
        scroll.setContentDescription("Test list");
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        int height = Math.round(64 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < 50; i++) {
            TextView row = new TextView(this);
            row.setText("Row " + i);
            rows.addView(row, -1, height);
        }
        scroll.addView(rows);
        form.addView(scroll, -1, -1);
        setContentView(form);
    }
}
