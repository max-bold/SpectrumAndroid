package com.bm.spectrum;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(SpectrumPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
