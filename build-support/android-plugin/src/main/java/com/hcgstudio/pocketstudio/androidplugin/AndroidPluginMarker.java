package com.hcgstudio.pocketstudio.androidplugin;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class AndroidPluginMarker extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
}
