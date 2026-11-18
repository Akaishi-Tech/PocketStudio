package __PACKAGE__;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.view.View;

public class MainActivity extends Activity {
    private int count = 0;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);
        final Button button = (Button) findViewById(R.id.hello_button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                count++;
                button.setText("Hello " + count);
            }
        });
    }
}
