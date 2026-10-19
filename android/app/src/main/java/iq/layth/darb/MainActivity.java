package iq.layth.darb;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // App-local plugins must be registered before the bridge starts.
        registerPlugin(DashboardPlugin.class);
        registerPlugin(NavBackgroundPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
