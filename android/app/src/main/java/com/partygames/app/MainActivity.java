package com.partygames.app;

import com.getcapacitor.BridgeActivity;
import java.util.Arrays;
import java.util.List;
import com.getcapacitor.PluginHandle;
import android.os.Bundle;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PartyGamesPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
