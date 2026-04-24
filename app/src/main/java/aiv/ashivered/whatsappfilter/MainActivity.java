package aiv.ashivered.whatsappfilter;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences sharedPreferences;
    private Button btnEnableAccessibility;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences("FilterPrefs", MODE_PRIVATE);
        btnEnableAccessibility = findViewById(R.id.btn_enable_accessibility);

        // הגדרת כפתור ההפעלה
        btnEnableAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        setupSwitch(R.id.switch_updates, "block_updates");
        setupSwitch(R.id.switch_channels, "block_channels");
        setupSwitch(R.id.switch_video, "block_video");
        setupSwitch(R.id.switch_block_status, "block_status");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // בדיקה בכל פעם שחוזרים למסך האם השירות הופעל
        if (isAccessibilityServiceEnabled(this, WhatsappFilterService.class)) {
            btnEnableAccessibility.setVisibility(View.GONE);
        } else {
            btnEnableAccessibility.setVisibility(View.VISIBLE);
        }
    }

    private void setupSwitch(int resId, final String prefKey) {
        SwitchCompat sw = findViewById(resId);
        if (sw != null) {
            sw.setChecked(sharedPreferences.getBoolean(prefKey, false));
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                sharedPreferences.edit().putBoolean(prefKey, isChecked).apply();
            });
        }
    }

    /**
     * פונקציית עזר שבודקת האם שירות נגישות ספציפי מופעל במערכת
     */
    public static boolean isAccessibilityServiceEnabled(Context context, Class<?> service) {
        String prefString = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (prefString != null) {
            return prefString.contains(context.getPackageName() + "/" + service.getName());
        }
        return false;
    }
}