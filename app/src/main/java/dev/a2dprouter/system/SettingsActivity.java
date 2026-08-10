package dev.a2dprouter.system;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.color.MaterialColors;

import io.github.libxposed.service.XposedService;

/** Material 3 configuration surface backed by LSPosed Remote Preferences. */
public final class SettingsActivity extends AppCompatActivity
        implements ModuleApplication.ServiceStateListener {
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (sharedPreferences, key) -> {
                if (key == null || ModulePreferences.KEY_ENABLED.equals(key)) {
                    runOnUiThread(this::renderPreference);
                }
            };

    private MaterialSwitch enabledSwitch;
    private TextView stateText;
    private SharedPreferences preferences;
    private boolean suppressSwitchCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_settings);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        enabledSwitch = findViewById(R.id.interception_switch);
        stateText = findViewById(R.id.state_text);
        enabledSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitchCallback || preferences == null) {
                return;
            }
            preferences.edit()
                    .putBoolean(ModulePreferences.KEY_ENABLED, checked)
                    .apply();
        });
        renderUnavailable();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addServiceStateListener(this);
    }

    @Override
    protected void onStop() {
        ModuleApplication.removeServiceStateListener(this);
        bindPreferences(null);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged(XposedService service) {
        runOnUiThread(() -> {
            if (service == null) {
                bindPreferences(null);
                return;
            }
            try {
                bindPreferences(service.getRemotePreferences(ModulePreferences.GROUP_ROUTING));
            } catch (RuntimeException error) {
                bindPreferences(null);
            }
        });
    }

    private void bindPreferences(SharedPreferences next) {
        if (preferences != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        }
        preferences = next;
        if (preferences == null) {
            renderUnavailable();
            return;
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        renderPreference();
    }

    private void renderPreference() {
        if (preferences == null) {
            renderUnavailable();
            return;
        }
        boolean enabled = preferences.getBoolean(ModulePreferences.KEY_ENABLED, true);
        suppressSwitchCallback = true;
        try {
            enabledSwitch.setChecked(enabled);
        } finally {
            suppressSwitchCallback = false;
        }
        enabledSwitch.setEnabled(true);
        stateText.setText(enabled ? R.string.state_enabled : R.string.state_disabled);
        stateText.setTextColor(MaterialColors.getColor(
                stateText,
                enabled
                        ? androidx.appcompat.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorOnSurfaceVariant));
    }

    private void renderUnavailable() {
        if (enabledSwitch == null || stateText == null) {
            return;
        }
        suppressSwitchCallback = true;
        try {
            enabledSwitch.setChecked(false);
        } finally {
            suppressSwitchCallback = false;
        }
        enabledSwitch.setEnabled(false);
        stateText.setText(R.string.state_unavailable);
        stateText.setTextColor(MaterialColors.getColor(
                stateText,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
    }
}
