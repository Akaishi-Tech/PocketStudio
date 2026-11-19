package __PACKAGE__

import android.app.Activity
import android.os.Bundle
import android.widget.Button

class MainActivity : Activity() {
    private var count = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        val button = findViewById<Button>(R.id.hello_button)
        button.setOnClickListener {
            count++
            button.text = "Hello $count"
        }
    }
}
