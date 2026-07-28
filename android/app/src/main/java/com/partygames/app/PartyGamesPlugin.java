package com.partygames.app;

import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.IOException;

@CapacitorPlugin(name = "PartyGames")
public class PartyGamesPlugin extends Plugin {

    private static final String TAG = "PartyGamesPlugin";
    private PartyGamesServer server = null;
    private static final int PORT = 3456;

    @PluginMethod
    public void startServer(PluginCall call) {
        if (server != null) {
            JSObject ret = new JSObject();
            ret.put("ip", server.getHostIp());
            ret.put("port", PORT);
            ret.put("running", true);
            call.resolve(ret);
            return;
        }

        try {
            server = new PartyGamesServer(getContext(), PORT);
            server.start();
            Log.i(TAG, "Server started on " + server.getHostIp() + ":" + PORT);

            JSObject ret = new JSObject();
            ret.put("ip", server.getHostIp());
            ret.put("port", PORT);
            ret.put("running", true);
            call.resolve(ret);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start server", e);
            call.reject("Failed to start server: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopServer(PluginCall call) {
        if (server != null) {
            server.stop();
            server = null;
            Log.i(TAG, "Server stopped");
        }
        JSObject ret = new JSObject();
        ret.put("running", false);
        call.resolve(ret);
    }

    @PluginMethod
    public void getServerInfo(PluginCall call) {
        JSObject ret = new JSObject();
        if (server != null && server.isAlive()) {
            ret.put("ip", server.getHostIp());
            ret.put("port", PORT);
            ret.put("running", true);
        } else {
            ret.put("running", false);
        }
        call.resolve(ret);
    }
}
