package ua.codex.phonesensors;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;

public class MqttPublisherService extends Service {
    private static final String TAG = "PhoneSensorsMQTT";
    private volatile boolean running;
    private void setStatus(String value) { getSharedPreferences("config", MODE_PRIVATE).edit().putString("service_status", value).apply(); }
    private Thread worker;
    private MqttConnection client;
    private SensorManager sensorManager;
    private final java.util.Map<Integer, SensorSpec> sensorSpecs = new java.util.HashMap<>();
    private final java.util.Map<String, Float> sensorReadings = new java.util.HashMap<>();
    private final Object sensorLock = new Object();

    @Override public void onCreate() { super.onCreate(); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        getSharedPreferences("config", MODE_PRIVATE).edit().putBoolean("enabled", true).putString("service_status", "Starting MQTT…").apply();
        startForeground(7, makeNotification());
        if (worker == null || !worker.isAlive()) { initializeHardwareSensors(); running = true; worker = new Thread(this::publishLoop, "mqtt-publisher"); worker.start(); }
        return START_STICKY;
    }
    private void initializeHardwareSensors() {
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) return;
        addSensor(Sensor.TYPE_ACCELEROMETER, "accelerometer", "Акселерометр", "m/s²", 3, null);
        addSensor(Sensor.TYPE_GYROSCOPE, "gyroscope", "Гіроскоп", "rad/s", 3, null);
        addSensor(Sensor.TYPE_MAGNETIC_FIELD, "magnetic_field", "Магнітне поле", "µT", 3, null);
        addSensor(Sensor.TYPE_GRAVITY, "gravity", "Гравітація", "m/s²", 3, null);
        addSensor(Sensor.TYPE_LINEAR_ACCELERATION, "linear_acceleration", "Лінійне прискорення", "m/s²", 3, null);
        addSensor(Sensor.TYPE_ORIENTATION, "orientation", "Орієнтація", "°", 3, null);
        addSensor(Sensor.TYPE_LIGHT, "ambient_light", "Освітленість", "lx", 1, "illuminance");
        addSensor(Sensor.TYPE_PROXIMITY, "proximity", "Наближення", "cm", 1, null);
        addSensor(Sensor.TYPE_PRESSURE, "air_pressure", "Атмосферний тиск", "hPa", 1, "pressure");
        addSensor(Sensor.TYPE_AMBIENT_TEMPERATURE, "ambient_temperature", "Температура повітря", "°C", 1, "temperature");
        addSensor(Sensor.TYPE_RELATIVE_HUMIDITY, "relative_humidity", "Вологість повітря", "%", 1, "humidity");
    }
    private void addSensor(int type, String key, String name, String unit, int axes, String deviceClass) {
        Sensor sensor = sensorManager.getDefaultSensor(type);
        if (sensor != null) {
            SensorSpec spec = new SensorSpec(type, key, name, unit, axes, deviceClass);
            sensorSpecs.put(type, spec);
            sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }
    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            SensorSpec spec = sensorSpecs.get(event.sensor.getType());
            if (spec == null) return;
            synchronized (sensorLock) {
                for (int i=0; i<spec.axes && i<event.values.length; i++) sensorReadings.put(spec.key + (spec.axes == 1 ? "" : "_" + "xyz".charAt(i)), event.values[i]);
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };
    private static final class SensorSpec {
        final int type, axes; final String key, name, unit, deviceClass;
        SensorSpec(int type, String key, String name, String unit, int axes, String deviceClass) { this.type=type; this.key=key; this.name=name; this.unit=unit; this.axes=axes; this.deviceClass=deviceClass; }
    }
    private Notification makeNotification() {
        String lang = getSharedPreferences("config", MODE_PRIVATE).getString("language", "uk");
        String title = "en".equals(lang) ? "Phone Sensors MQTT" : ("ru".equals(lang) ? "Датчики телефона MQTT" : "Датчики телефона MQTT");
        String message = "en".equals(lang) ? "Sending phone sensors to Home Assistant" : ("ru".equals(lang) ? "Передача датчиков в Home Assistant" : "Передавання датчиків у Home Assistant");
        String channel = "phone_sensors_mqtt";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(channel, "Phone sensor reporting", NotificationManager.IMPORTANCE_LOW));
        }
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, channel).setContentTitle(title).setContentText(message).setSmallIcon(android.R.drawable.stat_notify_sync).build();
        return new Notification.Builder(this).setContentTitle(title).setContentText(message).setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build();
    }
    private void publishLoop() {
        android.content.SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        String id = "android_phone_" + android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        String base = "phonesensors/" + id;
        String stateTopic = base + "/state", availabilityTopic = base + "/availability";
        while (running) {
            try {
                if (client == null || !client.isConnected()) connect(prefs, id, availabilityTopic);
                if (client != null && client.isConnected()) {
                    discover(id, stateTopic, availabilityTopic);
                    JSONObject data = readSensors();
                    publish(stateTopic, data.toString(), true);
                    setStatus(localized("Connected to MQTT; sensor data sent", "Підключено до MQTT; дані сенсорів надіслано", "Подключено к MQTT; данные датчиков отправлены"));
                    int delay = Math.max(15, Integer.parseInt(prefs.getString("interval", "60"))) * 1000;
                    for (int elapsed=0; running && elapsed<delay; elapsed+=1000) {
                        Thread.sleep(1000);
                        if (client != null && client.isConnected()) client.pingIfDue();
                        if (elapsed + 1000 >= delay && client != null && client.isConnected()) publish(stateTopic, readSensors().toString(), true);
                    }
                } else Thread.sleep(5000);
            } catch (Exception e) {
                Log.w(TAG, "MQTT cycle failed", e);
                String reason = e.getMessage(); if (reason == null || reason.length() == 0) reason = e.getClass().getSimpleName();
                setStatus("MQTT error: " + reason);
                closeClient();
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
            }
        }
        closeClient();
    }
    private void connect(android.content.SharedPreferences p, String id, String availability) throws Exception {
        String host = p.getString("host", ""); int port = Integer.parseInt(p.getString("port", "1883"));
        setStatus(localized("Connecting to " + host + ":" + port, "Підключаюся до " + host + ":" + port, "Подключаюсь к " + host + ":" + port));
        client = new MqttConnection();
        client.connect(host, port, "phone_" + id, p.getString("username", ""), p.getString("password", ""), availability, "offline");
        publish(availability, "online", true);
    }
    private void discover(String id, String state, String availability) throws Exception {
        sensor(id, "battery", "Батарея", state, availability, "battery", "%", "{{ value_json.battery }}", "measurement");
        sensor(id, "charging", "Заряджання", state, availability, null, null, "{{ value_json.charging }}", null, true);
        sensor(id, "battery_temperature", "Температура батареї", state, availability, "temperature", "°C", "{{ value_json.battery_temperature }}", "measurement");
        sensor(id, "battery_voltage", "Напруга батареї", state, availability, "voltage", "V", "{{ value_json.battery_voltage }}", "measurement");
        sensor(id, "battery_health", "Стан батареї", state, availability, null, null, "{{ value_json.battery_health }}", null);
        sensor(id, "battery_power_source", "Живлення", state, availability, null, null, "{{ value_json.battery_power_source }}", null);
        sensor(id, "battery_technology", "Тип батареї", state, availability, null, null, "{{ value_json.battery_technology }}", null);
        sensor(id, "wifi_connected", "Wi-Fi підключено", state, availability, null, null, "{{ value_json.wifi_connected }}", null, true);
        sensor(id, "wifi_signal", "Сигнал Wi-Fi", state, availability, "signal_strength", "dBm", "{{ value_json.wifi_rssi }}", "measurement");
        sensor(id, "wifi_ssid", "Назва Wi-Fi", state, availability, null, null, "{{ value_json.wifi_ssid }}", null);
        sensor(id, "network_connected", "Мережа підключена", state, availability, null, null, "{{ value_json.network_connected }}", null, true);
        sensor(id, "network_type", "Тип мережі", state, availability, null, null, "{{ value_json.network_type }}", null);
        sensor(id, "network_metered", "Мережа з тарифікацією", state, availability, null, null, "{{ value_json.network_metered }}", null, true);
        sensor(id, "device_model", "Модель телефона", state, availability, null, null, "{{ value_json.device_model }}", null);
        sensor(id, "android_version", "Версія Android", state, availability, null, null, "{{ value_json.android_version }}", null);
        sensor(id, "ram_available", "Вільна оперативна пам’ять", state, availability, null, "MB", "{{ value_json.ram_available_mb }}", "measurement");
        sensor(id, "ram_total", "Оперативна пам’ять усього", state, availability, null, "MB", "{{ value_json.ram_total_mb }}", "measurement");
        sensor(id, "storage_available", "Вільне внутрішнє сховище", state, availability, null, "MB", "{{ value_json.storage_available_mb }}", "measurement");
        sensor(id, "storage_total", "Внутрішнє сховище усього", state, availability, null, "MB", "{{ value_json.storage_total_mb }}", "measurement");
        sensor(id, "uptime", "Час від запуску", state, availability, "duration", "s", "{{ value_json.uptime_seconds }}", "measurement");
        sensor(id, "screen_on", "Екран увімкнений", state, availability, null, null, "{{ value_json.screen_on }}", null, true);
        sensor(id, "hardware_sensor_count", "Датчиків у телефоні", state, availability, null, null, "{{ value_json.hardware_sensor_count }}", "measurement");
        sensor(id, "hardware_sensor_list", "Датчики телефона", state, availability, null, null, "{{ value_json.hardware_sensor_list }}", null);
        for (SensorSpec spec : sensorSpecs.values()) {
            if (spec.axes == 1) sensor(id, "hw_" + spec.key, spec.name, state, availability, spec.deviceClass, spec.unit, "{{ value_json.hw_" + spec.key + " }}", "measurement");
            else for (int i=0; i<spec.axes; i++) { String axis = "xyz".substring(i,i+1); sensor(id, "hw_" + spec.key + "_" + axis, spec.name + " " + axis.toUpperCase(), state, availability, null, spec.unit, "{{ value_json.hw_" + spec.key + "_" + axis + " }}", "measurement"); }
        }
    }
    private void sensor(String id, String key, String name, String state, String availability, String deviceClass, String unit, String template, String stateClass) throws Exception { sensor(id,key,name,state,availability,deviceClass,unit,template,stateClass,false); }
    private void sensor(String id, String key, String name, String state, String availability, String deviceClass, String unit, String template, String stateClass, boolean binary) throws Exception {
        String component = binary ? "binary_sensor" : "sensor";
        String topic = "homeassistant/" + component + "/" + id + "/" + key + "/config";
        JSONObject config = new JSONObject();
        config.put("name", name); config.put("unique_id", id + "_" + key); config.put("state_topic", state); config.put("availability_topic", availability);
        config.put("value_template", template); config.put("payload_available", "online"); config.put("payload_not_available", "offline"); config.put("qos", 1);
        if (deviceClass != null) config.put("device_class", deviceClass);
        if (unit != null) config.put("unit_of_measurement", unit);
        if (stateClass != null) config.put("state_class", stateClass);
        if (binary) { config.put("payload_on", "ON"); config.put("payload_off", "OFF"); }
        JSONObject dev = new JSONObject(); dev.put("identifiers", new org.json.JSONArray().put(id)); dev.put("name", getSharedPreferences("config", MODE_PRIVATE).getString("device", "Android Phone")); dev.put("manufacturer", "Android"); dev.put("model", "Phone Sensors MQTT"); config.put("device", dev);
        publish(topic, config.toString(), true);
    }
    private JSONObject readSensors() throws Exception {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery == null ? 100 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = (level < 0 || scale <= 0) ? -1 : Math.round(level * 100f / scale);
        int status = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int health = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        float batteryTemp = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f;
        float batteryVoltage = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) / 1000f;
        String healthText = health == BatteryManager.BATTERY_HEALTH_GOOD ? "GOOD" : health == BatteryManager.BATTERY_HEALTH_OVERHEAT ? "OVERHEAT" : health == BatteryManager.BATTERY_HEALTH_DEAD ? "DEAD" : health == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ? "OVER_VOLTAGE" : health == BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ? "FAILURE" : "UNKNOWN";
        String powerSource = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC" : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB" : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "WIRELESS" : plugged == 0 ? "NONE" : "UNKNOWN";
        String technology = battery == null ? "unknown" : battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
        if (technology == null) technology = "unknown";

        WifiManager wm = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
        boolean wifiConnected = wi != null && wi.getNetworkId() != -1 && wi.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED;
        String ssid = wifiConnected && wi.getSSID() != null ? wi.getSSID().replace("\"", "") : "unknown";
        int rssi = wifiConnected ? wi.getRssi() : -127;

        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        boolean networkConnected = ni != null && ni.isConnected();
        String networkType = networkConnected ? ni.getTypeName() + (ni.getSubtypeName() == null || ni.getSubtypeName().isEmpty() ? "" : " / " + ni.getSubtypeName()) : "disconnected";
        boolean metered = Build.VERSION.SDK_INT >= 16 && cm != null && cm.isActiveNetworkMetered();

        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        ((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        long totalRam = Build.VERSION.SDK_INT >= 16 ? mi.totalMem : readTotalRam();
        android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
        long blockSize = fs.getBlockSize();
        long totalStorage = Build.VERSION.SDK_INT >= 18 ? fs.getTotalBytes() : (long)fs.getBlockCount() * blockSize;
        long freeStorage = Build.VERSION.SDK_INT >= 18 ? fs.getAvailableBytes() : (long)fs.getAvailableBlocks() * blockSize;
        android.hardware.SensorManager sm = (android.hardware.SensorManager)getSystemService(SENSOR_SERVICE);
        java.util.List<android.hardware.Sensor> sensors = sm == null ? java.util.Collections.<android.hardware.Sensor>emptyList() : sm.getSensorList(android.hardware.Sensor.TYPE_ALL);
        StringBuilder sensorNames = new StringBuilder();
        for (int i=0; i<sensors.size() && i<30; i++) { if (i>0) sensorNames.append(", "); sensorNames.append(sensors.get(i).getName()); }
        android.os.PowerManager pm = (android.os.PowerManager)getSystemService(POWER_SERVICE);
        boolean screenOn = pm != null && pm.isScreenOn();

        JSONObject json = new JSONObject();
        json.put("battery", pct); json.put("charging", charging ? "ON" : "OFF");
        json.put("battery_temperature", batteryTemp); json.put("battery_voltage", batteryVoltage);
        json.put("battery_health", healthText); json.put("battery_power_source", powerSource); json.put("battery_technology", technology);
        json.put("wifi_connected", wifiConnected ? "ON" : "OFF"); json.put("wifi_ssid", ssid); json.put("wifi_rssi", rssi);
        json.put("network_connected", networkConnected ? "ON" : "OFF"); json.put("network_type", networkType); json.put("network_metered", metered ? "ON" : "OFF");
        json.put("device_model", Build.MANUFACTURER + " " + Build.MODEL); json.put("android_version", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        json.put("ram_available_mb", mi.availMem / 1048576L); json.put("ram_total_mb", totalRam / 1048576L);
        json.put("storage_available_mb", freeStorage / 1048576L); json.put("storage_total_mb", totalStorage / 1048576L);
        json.put("uptime_seconds", android.os.SystemClock.elapsedRealtime() / 1000L); json.put("screen_on", screenOn ? "ON" : "OFF");
        json.put("hardware_sensor_count", sensors.size()); json.put("hardware_sensor_list", sensorNames.toString());
        synchronized (sensorLock) { for (java.util.Map.Entry<String, Float> value : sensorReadings.entrySet()) json.put("hw_" + value.getKey(), value.getValue()); }
        return json;
    }
    private long readTotalRam() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"));
            String line = reader.readLine(); reader.close();
            if (line != null) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("MemTotal:\\s+(\\d+)").matcher(line); if (m.find()) return Long.parseLong(m.group(1)) * 1024L; }
        } catch (Exception ignored) { }
        return 0;
    }
    private void publish(String topic, String text, boolean retained) throws Exception {
        client.publish(topic, text, retained);
    }
    private String localized(String en, String uk, String ru) { String lang = getSharedPreferences("config", MODE_PRIVATE).getString("language", "uk"); return "en".equals(lang) ? en : ("ru".equals(lang) ? ru : uk); }
    private void closeClient() { try { if (client != null) { client.close(); } } catch (Exception ignored) { } client = null; }
    @Override public void onDestroy() {
        try {
            String id = "android_phone_" + android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (client != null && client.isConnected()) publish("phonesensors/" + id + "/availability", "offline", true);
        } catch (Exception ignored) { }
        running = false;
        getSharedPreferences("config", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        if (worker != null) worker.interrupt();
        stopForeground(true); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}








