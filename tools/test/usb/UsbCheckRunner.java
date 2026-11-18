package com.hcgstudio.pocketstudio.adb;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

public final class UsbCheckRunner extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            TransportCheck.main(new String[]{"android"});
            result.putString("result", "PASS USB bulk fixture and AndroidKeyStore RSA AUTH");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
