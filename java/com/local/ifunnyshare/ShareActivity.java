package com.local.ifunnyshare;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import java.io.File;
import org.kivy.android.PythonActivity;

/** A native share receiver: starts the download service and immediately finishes. */
public class ShareActivity extends Activity {
    private static final int NOTIFICATIONS = 72;
    private String sharedText;

    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Translucent_NoTitleBar);
        super.onCreate(state);
        CharSequence text = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        sharedText = text == null ? null : text.toString();
        File app = new File(getFilesDir(), "app");
        boolean unpacked = new File(app, "main.py").isFile()
            || new File(app, "main.pyc").isFile()
            || new File(app, "main.pyo").isFile();
        if (sharedText == null || !unpacked) {
            // PythonActivity performs p4a's one-time extraction of the Python assets.
            Intent setup = new Intent(this, PythonActivity.class);
            if (sharedText != null) setup.putExtra(Intent.EXTRA_TEXT, sharedText);
            startActivity(setup);
            finish();
            return;
        }
        boolean asked = getPreferences(MODE_PRIVATE).getBoolean("notifications_asked", false);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !asked) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else {
            handOff();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == NOTIFICATIONS) handOff();
    }

    private void handOff() {
        try {
            // We are still a visible Activity here, so service launch is permitted.
            ServiceDownloader.start(this, "", "iFunny Downloader", "Starting download…", sharedText);
        } catch (Exception error) {
            Toast.makeText(this, "Could not start download: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
        // Do not remove the task; the caller may share into its own task.
        finish();
        overridePendingTransition(0, 0);
    }
}
