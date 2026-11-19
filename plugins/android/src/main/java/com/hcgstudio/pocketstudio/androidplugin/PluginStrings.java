package com.hcgstudio.pocketstudio.androidplugin;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

final class PluginStrings {
    private static Context host, plugin;
    private static Resources resources;
    private static Configuration configuration;

    static synchronized void initialize(Context hostContext, Context pluginContext) {
        host = hostContext;
        plugin = pluginContext;
        configuration = null;
    }

    static synchronized String uiString(int id, Object... arguments) {
        Configuration current = host.getResources().getConfiguration();
        if (!current.equals(configuration)) {
            configuration = new Configuration(current);
            resources = plugin.createConfigurationContext(configuration).getResources();
        }
        return resources.getString(id, arguments);
    }
}
