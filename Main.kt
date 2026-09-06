package com.example.weatherapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onPermissionResult?.invoke(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleWidgetUpdates(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WeatherApp(
                        hasPermission = { hasLocationPermission() },
                        requestPermission = { callback ->
                            onPermissionResult = callback
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    )
                }
            }
        }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private sealed class UiState {
    data object Loading : UiState()
    data object PermissionDenied : UiState()
    data class Success(val current: WeatherResponse, val forecast: List<DailyForecast>) : UiState()
    data class Error(val message: String) : UiState()
}

@Composable
private fun WeatherApp(
    hasPermission: () -> Boolean,
    requestPermission: ((Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<UiState>(UiState.Loading) }
    var location by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    suspend fun loadWeather(lat: Double, lon: Double) {
        uiState = UiState.Loading
        try {
            val current = WeatherApi.service.getCurrentWeather(lat, lon, WeatherApi.API_KEY)
            val forecastResponse = WeatherApi.service.getForecast(lat, lon, WeatherApi.API_KEY)
            uiState = UiState.Success(current, aggregateToDailyForecast(forecastResponse))
        } catch (e: Exception) {
            uiState = UiState.Error(e.message ?: "Failed to load weather data.")
        }
    }

    fun fetchLocationAndWeather() {
        scope.launch {
            uiState = UiState.Loading
            val loc = getCurrentLocation(context)
            if (loc == null) {
                uiState = UiState.Error("Could not determine current location.")
            } else {
                location = loc
                loadWeather(loc.first, loc.second)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (hasPermission()) {
            fetchLocationAndWeather()
        } else {
            requestPermission { granted ->
                if (granted) fetchLocationAndWeather() else uiState = UiState.PermissionDenied
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Weather", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = {
                val loc = location
                if (loc != null) scope.launch { loadWeather(loc.first, loc.second) } else fetchLocationAndWeather()
            }) { Text("Refresh") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val state = uiState) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.PermissionDenied -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Location permission is required to show local weather.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = {
                        requestPermission { granted ->
                            if (granted) fetchLocationAndWeather() else uiState = UiState.PermissionDenied
                        }
                    }) { Text("Grant Permission") }
                }
            }
            is UiState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(state.message)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { fetchLocationAndWeather() }) { Text("Retry") }
                }
            }
            is UiState.Success -> WeatherContent(current = state.current, forecast = state.forecast)
        }
    }
}

@Composable
private fun WeatherContent(current: WeatherResponse, forecast: List<DailyForecast>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { CurrentWeatherCard(current) }
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text("5-Day Forecast", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(forecast) { day -> ForecastRow(day) }
    }
}

@Composable
private fun CurrentWeatherCard(weather: WeatherResponse) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = weather.name, style = MaterialTheme.typography.headlineSmall)

            val iconCode = weather.weather.firstOrNull()?.icon ?: "01d"
            AsyncImage(
                model = "https://openweathermap.org/img/wn/$iconCode@2x.png",
                contentDescription = weather.weather.firstOrNull()?.description,
                modifier = Modifier.size(96.dp)
            )

            Text("${weather.main.temp.toInt()}°C", style = MaterialTheme.typography.displayMedium)
            Text(weather.weather.firstOrNull()?.description?.replaceFirstChar { it.uppercase() } ?: "")

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DetailItem("Humidity", "${weather.main.humidity}%")
                DetailItem("Wind", "${weather.wind.speed} m/s")
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DetailItem("Sunrise", formatTime(weather.sys.sunrise))
                DetailItem("Sunset", formatTime(weather.sys.sunset))
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ForecastRow(day: DailyForecast) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = day.date, modifier = Modifier.weight(1f))
        AsyncImage(
            model = "https://openweathermap.org/img/wn/${day.icon}.png",
            contentDescription = day.description,
            modifier = Modifier.size(40.dp)
        )
        Text(text = "${day.maxTemp.toInt()}° / ${day.minTemp.toInt()}°")
    }
}

private fun formatTime(unixSeconds: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(unixSeconds * 1000L))
}
