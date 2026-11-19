package com.hcgstudio.pocketstudio.avfplugin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.hcgstudio.pocketstudio.linuxipc.RuntimeConnection;
import com.hcgstudio.pocketstudio.plugin.LinuxEnvironment;

import java.io.IOException;

public final class AvfEnvironment implements LinuxEnvironment {
    private final Context host;
    private final Context plugin;

    public AvfEnvironment(Context host, Context plugin) {
        this.host = host;
        this.plugin = plugin;
    }

    @Override public Intent setupIntent() {
        return new Intent().setClassName(plugin.getPackageName(),
            "com.hcgstudio.pocketstudio.avfplugin.AvfSetupActivity");
    }

    @Override public Process start(String project, String[] argv, boolean terminal) throws IOException {
        return RuntimeConnection.start(host, new ComponentName(plugin.getPackageName(),
            "com.hcgstudio.pocketstudio.avfplugin.AvfRuntimeService"), project, argv, terminal);
    }
}
