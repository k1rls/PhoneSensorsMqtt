package ua.codex.phonesensors;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 42;
    private EditText host, port, username, password, device, interval;
    private TextView title, intro, info, note;
    private Spinner languagePicker;
    private Button startButton, stopButton, helpButton;
    private SharedPreferences prefs;
    private String language = "uk";
    private boolean startAfterPermission;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() { public void run() { if (info != null) { String s = prefs.getString("service_status", ""); if (!s.isEmpty()) info.setText(s); } statusHandler.postDelayed(this, 1000); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        language = prefs.getString("language", deviceLanguage());
        buildUi();
        load();
        updateStrings();
    }

    @Override protected void onResume() { super.onResume(); statusHandler.removeCallbacks(statusRefresh); statusHandler.post(statusRefresh); }
    @Override protected void onPause() { statusHandler.removeCallbacks(statusRefresh); super.onPause(); }
    private String deviceLanguage() {
        String l = java.util.Locale.getDefault().getLanguage();
        return "ru".equals(l) ? "ru" : ("uk".equals(l) ? "uk" : "en");
    }
    private String tr(String en, String uk, String ru) { return "en".equals(language) ? en : ("ru".equals(language) ? ru : uk); }
    private int languageIndex() { return "en".equals(language) ? 0 : ("ru".equals(language) ? 2 : 1); }
    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + .5f); }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16); box.setPadding(pad, pad, pad, pad); scroll.addView(box);

        title = new TextView(this); title.setTextSize(21); title.setPadding(0,0,0,dp(4)); box.addView(title);
        intro = new TextView(this); intro.setTextSize(14); box.addView(intro);

        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(0,dp(4),0,dp(2)); box.addView(top);
        languagePicker = new Spinner(this);
        String[] languageNames = new String[]{"English", "Українська", "кацапська мова"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, languageNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); languagePicker.setAdapter(adapter);
        top.addView(languagePicker, new LinearLayout.LayoutParams(0, dp(48), 1));
        helpButton = new Button(this); top.addView(helpButton, new LinearLayout.LayoutParams(dp(94), dp(46)));
        languagePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                language = position == 0 ? "en" : (position == 2 ? "ru" : "uk");
                prefs.edit().putString("language", language).apply(); updateStrings();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        languagePicker.setSelection(languageIndex(), false);

        host = field(box); port = field(box); username = field(box); password = field(box); device = field(box); interval = field(box);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port.setInputType(InputType.TYPE_CLASS_NUMBER); interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); buttons.setGravity(Gravity.CENTER); box.addView(buttons);
        startButton = new Button(this); buttons.addView(startButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        stopButton = new Button(this); buttons.addView(stopButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        info = new TextView(this); info.setTextSize(14); info.setPadding(0,dp(8),0,0); box.addView(info);
        note = new TextView(this); note.setTextSize(12); note.setPadding(0,dp(10),0,0); box.addView(note);

        helpButton.setOnClickListener(v -> showHelp());
        startButton.setOnClickListener(v -> saveAndStart());
        stopButton.setOnClickListener(v -> { stopService(new Intent(this, MqttPublisherService.class)); info.setText(tr("Reporting stopped.", "Передавання зупинено.", "Передача остановлена.")); });
        setContentView(scroll);
    }

    private EditText field(LinearLayout box) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setTextSize(16); box.addView(e, new LinearLayout.LayoutParams(-1, dp(50))); return e;
    }

    private void updateStrings() {
        title.setText(tr("Phone → Home Assistant", "Телефон → Home Assistant", "Телефон → Home Assistant"));
        intro.setText(tr("Send phone diagnostics to Home Assistant over MQTT.", "Передавай дані телефона в Home Assistant через MQTT.", "Передавайте данные телефона в Home Assistant через MQTT."));
        helpButton.setText(tr("Help", "Довідка", "Помощь"));
        host.setHint(tr("MQTT broker host or IP", "Адреса MQTT-брокера або IP", "Адрес MQTT-брокера или IP"));
        port.setHint(tr("Port (1883 / TLS 8883)", "Порт (1883 / TLS 8883)", "Порт (1883 / TLS 8883)"));
        username.setHint(tr("Username (optional)", "Логін (необов’язково)", "Логин (необязательно)"));
        password.setHint(tr("Password (optional)", "Пароль (необов’язково)", "Пароль (необязательно)"));
        device.setHint(tr("Your device name", "Назва твого пристрою", "Имя твоего устройства"));
        interval.setHint(tr("Update interval, seconds", "Інтервал оновлення, секунд", "Интервал обновления, секунд"));
        startButton.setText(tr("Save & Start", "Зберегти й запустити", "Сохранить и запустить"));
        stopButton.setText(tr("Stop", "Зупинити", "Остановить"));
        note.setText(tr("Allow location only if you want the Wi-Fi name. Disable battery optimization if Android stops background reporting.", "Дозволь геолокацію, якщо потрібна назва Wi-Fi. Вимкни оптимізацію батареї, якщо Android зупиняє фонове передавання.", "Разрешите геолокацию, если нужно имя Wi-Fi. Отключите оптимизацию батареи, если Android останавливает фоновую передачу."));
    }

    private void load() {
        host.setText(prefs.getString("host", "")); port.setText(prefs.getString("port", "1883"));
        username.setText(prefs.getString("username", "")); password.setText(prefs.getString("password", ""));
        String detectedName = (Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER) + " " + Build.MODEL;
        String savedDevice = prefs.getString("device", null);
        if (savedDevice == null || savedDevice.trim().isEmpty() || "Samsung J1".equals(savedDevice)) savedDevice = detectedName;
        device.setText(savedDevice);
        interval.setText(prefs.getString("interval", "60"));
        info.setText(tr("Enter your broker details to begin.", "Вкажи дані MQTT-брокера, щоб почати.", "Укажи данные MQTT-брокера, чтобы начать."));
    }

    private void showHelp() {
        String message = tr(
            "1. Enter the MQTT broker host/IP (not the Home Assistant web address).\n2. Use port 1883, or 8883 when your broker is configured for TLS.\n3. Enter the broker login if required, then choose Save & Start.\n4. Make sure MQTT is configured in Home Assistant. The phone appears through MQTT Discovery.\n5. Location permission is only used by Android to reveal the Wi-Fi name.",
            "1. Введи адресу або IP MQTT-брокера (не вебадресу Home Assistant).\n2. Порт 1883 або 8883, якщо брокер налаштований на TLS.\n3. За потреби введи логін і пароль брокера та натисни «Зберегти й запустити».\n4. Переконайся, що MQTT інтеграція в Home Assistant підключена до цього ж брокера і MQTT Discovery увімкнено. Телефон додасться автоматично.\n5. Дозвіл геолокації потрібен Android лише для показу назви Wi-Fi.",
            "1. Укажи адрес или IP MQTT-брокера (не вебадрес Home Assistant).\n2. Порт 1883 или 8883, если брокер настроен на TLS.\n3. При необходимости введи логин и пароль брокера и нажми «Сохранить и запустить».\n4. Убедись, что интеграция MQTT в Home Assistant подключена к тому же брокеру и MQTT Discovery включен. Телефон добавится автоматически.\n5. Разрешение геолокации нужно Android только для отображения имени Wi-Fi.");
        new AlertDialog.Builder(this).setTitle(tr("Help", "Довідка", "Помощь")).setMessage(message).setPositiveButton(tr("Close", "Закрити", "Закрыть"), null).show();
    }

    private void saveAndStart() {
        String h = host.getText().toString().trim();
        if (h.isEmpty()) { host.setError(tr("Enter the broker host", "Введи адресу брокера", "Введи адрес брокера")); return; }
        int p, seconds;
        try { p = Integer.parseInt(port.getText().toString().trim()); if (p < 1 || p > 65535) throw new Exception(); }
        catch (Exception ex) { port.setError(tr("Port must be 1–65535", "Порт має бути від 1 до 65535", "Порт должен быть от 1 до 65535")); return; }
        try { seconds = Integer.parseInt(interval.getText().toString().trim()); if (seconds < 15) seconds = 15; }
        catch (Exception ex) { seconds = 60; }
        String detectedName = (Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER) + " " + Build.MODEL;
        String deviceName = device.getText().toString().trim(); if (deviceName.isEmpty()) deviceName = detectedName;
        prefs.edit().putString("host", h).putString("port", String.valueOf(p))
            .putString("username", username.getText().toString().trim()).putString("password", password.getText().toString())
            .putString("device", deviceName).putString("interval", String.valueOf(seconds)).putBoolean("enabled", true).apply();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true; requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST); return;
        }
        launchPublisher();
    }
    private void launchPublisher() {
        Intent i = new Intent(this, MqttPublisherService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        info.setText(tr("Starting MQTT reporting…", "Запускаю передавання MQTT…", "Запускаю передачу MQTT…"));
        Toast.makeText(this, tr("Reporting started", "Передавання запущено", "Передача запущена"), Toast.LENGTH_SHORT).show();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == LOCATION_REQUEST && startAfterPermission) { startAfterPermission = false; launchPublisher(); }
    }
}


