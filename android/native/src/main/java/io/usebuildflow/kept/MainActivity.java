package io.usebuildflow.kept;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override public void onCreate(Bundle savedInstanceState) {
        registerPlugin(KeptNativePlugin.class);   // must be registered before super.onCreate
        super.onCreate(savedInstanceState);
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    /** "Share → Kept" from a gig app's offer screenshot: the web app scans it on the Check an offer screen. */
    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String type = i.getType();
        if (type == null || !type.startsWith("image/")) return;
        Uri uri = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        KeptNativePlugin.setSharedImage(uri.toString(), type);
    }
}
