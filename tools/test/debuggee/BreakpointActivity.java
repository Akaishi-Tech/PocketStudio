package dev.ondevice.studio.debuggee;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

/** Separate debuggable ART process used by the cross-device JDWP instrumentation. */
public class BreakpointActivity extends Activity {
    private int count;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final Button button = new Button(this);
        button.setText("Trigger breakpoint");
        setContentView(button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                count++;
                button.setText("Hits " + count);
            }
        });
    }
}
