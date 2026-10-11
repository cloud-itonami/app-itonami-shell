package {{PACKAGE}};

// kotoba-shell auth/open-authorization-url — the Android redirect half.
//
// Generated only when the manifest declares :android/oauth-callback-scheme.
// AndroidManifest.xml then registers this Activity with a BROWSABLE
// intent-filter for exactly that scheme, and app/build.gradle gains
// androidx.browser; an app that declares no scheme carries neither.
//
// Two parts:
//   - CustomTabsLauncher opens the authorization URL in a Custom Tab, or in
//     the default browser when no installed browser provides Custom Tabs.
//     Never in the app's own WebView (RFC 8252 section 8.12).
//   - The Activity receives the redirect, hands it to KotobaShellBridge
//     (which resolves the waiting invoke() through the same
//     evaluateJavascript reply path every other command uses), and brings
//     MainActivity back to the front, closing the Custom Tab above it.

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;

import java.util.Collections;

public final class KotobaShellAuthCallbackActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri data = getIntent() == null ? null : getIntent().getData();
        // Unasked redirects (nothing pending, wrong scheme) are dropped by
        // the bridge; either way the user lands back in the app.
        KotobaShellBridge.deliverAuthorizationCallback(data);
        // CLEAR_TOP + SINGLE_TOP: reuse the existing MainActivity (and its
        // WebView, which holds the waiting promise) instead of recreating it,
        // and pop the Custom Tab that sits above it.
        Intent back = new Intent(this, MainActivity.class);
        back.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(back);
        finish();
    }

    public static final class CustomTabsLauncher implements KotobaShellBridge.AuthorizationLauncher {
        @Override
        public void launch(Activity activity, Uri uri) {
            // null when no installed browser offers the Custom Tabs service
            // (needs the <queries> entry the scaffold writes, or Android 11+
            // package visibility hides every provider and this is always null).
            String provider = CustomTabsClient.getPackageName(activity, Collections.<String>emptyList());
            if (provider == null) {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, uri)
                        .addCategory(Intent.CATEGORY_BROWSABLE));
                return;
            }
            CustomTabsIntent intent = new CustomTabsIntent.Builder().build();
            intent.intent.setPackage(provider);
            intent.launchUrl(activity, uri);
        }
    }
}
