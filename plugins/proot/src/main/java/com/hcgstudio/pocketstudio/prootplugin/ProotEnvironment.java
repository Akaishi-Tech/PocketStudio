package com.hcgstudio.pocketstudio.prootplugin;

import android.content.*;
import com.hcgstudio.pocketstudio.linuxipc.RuntimeConnection;
import com.hcgstudio.pocketstudio.plugin.LinuxEnvironment;
import java.io.IOException;

/** Host-side entry. Plugin files and processes stay in the plugin's own Android UID. */
public final class ProotEnvironment implements LinuxEnvironment {
    private final Context host, plugin;
    public ProotEnvironment(Context host, Context plugin) { this.host = host; this.plugin = plugin; }
    @Override public Intent setupIntent() {
        return new Intent().setClassName(plugin.getPackageName(), SetupActivity.class.getName());
    }
    @Override public Process start(String project, String[] argv, boolean terminal) throws IOException {
        return RuntimeConnection.start(host, new ComponentName(plugin, ProotService.class.getName()), project, argv, terminal);
    }
}
