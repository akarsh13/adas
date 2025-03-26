package com.example.adasapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var accelerationText by mutableStateOf("Acceleration: N/A")
    private var gyroscopeText by mutableStateOf("Cornering: N/A")
    private var speedText by mutableStateOf("Speed: N/A")
    private var weatherText by mutableStateOf("Weather: Fetching...")
    private var timeText by mutableStateOf("Time: --:--")
    private var dayText by mutableStateOf("Day: ---")
    private var roadInfoText by mutableStateOf("Road: Fetching...")

    private var latestAccel: FloatArray? = null
    private var latestGyro: FloatArray? = null
    private var latestSpeed: Float = 0f
    private var latestWeather: String = "Unknown"
    private var latestTime: String = "--:--"
    private var latestDay: String = "---"
    private var latestRoadInfo: String = "Unknown"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val speedMph = location.speed * 2.23694f
                    latestSpeed = speedMph
                    speedText = "Speed: %.2f mph".format(speedMph)

                    val now = Date()
                    latestTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                    latestDay = SimpleDateFormat("EEEE", Locale.getDefault()).format(now)
                    timeText = "Time: $latestTime"
                    dayText = "Day: $latestDay"

                    fetchWeather()
                    fetchRoadInfo(location.latitude, location.longitude)

                    logToCSV(latestAccel, latestGyro, latestSpeed, latestTime, latestDay, latestWeather, latestRoadInfo)
                }
            }
        }

        requestLocationPermission()

        setContent {
            ADASAppUI(
                acceleration = accelerationText,
                gyroscope = gyroscopeText,
                speed = speedText,
                time = timeText,
                day = dayText,
                weather = weatherText,
                road = roadInfoText
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    latestAccel = it.values.clone()
                    accelerationText = "Acceleration: x=%.2f, y=%.2f, z=%.2f".format(
                        it.values[0], it.values[1], it.values[2]
                    )
                }
                Sensor.TYPE_GYROSCOPE -> {
                    latestGyro = it.values.clone()
                    gyroscopeText = "Cornering: x=%.2f, y=%.2f, z=%.2f".format(
                        it.values[0], it.values[1], it.values[2]
                    )
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1
            )
        } else {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000
        ).build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        }
    }

    private fun fetchWeather() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = URL("https://wttr.in/?format=3").readText()
                latestWeather = result.trim()
                withContext(Dispatchers.Main) {
                    weatherText = "Weather: $latestWeather"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    weatherText = "Weather: Error"
                }
            }
        }
    }

    private fun fetchRoadInfo(lat: Double, lon: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon"
                val result = URL(url).readText()
                val obj = JSONObject(result)
                val road = obj.getJSONObject("address").optString("road", "Unknown Road")
                val type = obj.optString("type", "Unknown Type")
                latestRoadInfo = "$road ($type)"
                withContext(Dispatchers.Main) {
                    roadInfoText = "Road: $latestRoadInfo"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    roadInfoText = "Road: Error"
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        }
    }

    private fun logToCSV(
        accel: FloatArray?,
        gyro: FloatArray?,
        speedMph: Float,
        time: String,
        day: String,
        weather: String,
        road: String
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val accelX = accel?.getOrNull(0) ?: 0f
        val accelY = accel?.getOrNull(1) ?: 0f
        val accelZ = accel?.getOrNull(2) ?: 0f
        val gyroX = gyro?.getOrNull(0) ?: 0f
        val gyroY = gyro?.getOrNull(1) ?: 0f
        val gyroZ = gyro?.getOrNull(2) ?: 0f

        val data = "$timestamp,$day,$time,$accelX,$accelY,$accelZ,$gyroX,$gyroY,$gyroZ,$speedMph,$weather,$road\n"
        val file = File(getExternalFilesDir(null), "sensor_log.csv")

        if (!file.exists()) {
            file.writeText("Timestamp,Day,Time,Accel X,Accel Y,Accel Z,Gyro X,Gyro Y,Gyro Z,Speed (mph),Weather,Road Info\n")
        }

        FileWriter(file, true).use { it.append(data) }
    }
}

@Composable
fun ADASAppUI(
    acceleration: String,
    gyroscope: String,
    speed: String,
    time: String,
    day: String,
    weather: String,
    road: String
) {
    val context = LocalContext.current

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Acceleration & Braking Patterns", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = acceleration, style = MaterialTheme.typography.bodyLarge)
        Text(text = gyroscope, style = MaterialTheme.typography.bodyLarge)
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Cornering Behavior", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = gyroscope, style = MaterialTheme.typography.bodyLarge)
        Text(text = speed, style = MaterialTheme.typography.bodyLarge)
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Speed Consistency", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = speed, style = MaterialTheme.typography.bodyLarge)
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Lane Changes & Drifts", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = acceleration, style = MaterialTheme.typography.bodyLarge)
        Text(text = gyroscope, style = MaterialTheme.typography.bodyLarge)
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Environmental Context", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = day, style = MaterialTheme.typography.bodyLarge)
        Text(text = time, style = MaterialTheme.typography.bodyLarge)
        Text(text = weather, style = MaterialTheme.typography.bodyLarge)
        Text(text = road, style = MaterialTheme.typography.bodyLarge)
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Wearable Device Metrics (if available)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "HRV: Not Connected", style = MaterialTheme.typography.bodyLarge)
        Text(text = "Stress Level: Not Available", style = MaterialTheme.typography.bodyLarge)
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Button(onClick = { shareCSV(context) }) {
            Text("Share Sensor Log")
        }
    }
}

fun shareCSV(context: android.content.Context) {
    val file = File(context.getExternalFilesDir(null), "sensor_log.csv")
    if (!file.exists()) return

    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(
        Intent.createChooser(shareIntent, "Share Sensor Log")
    )
}
