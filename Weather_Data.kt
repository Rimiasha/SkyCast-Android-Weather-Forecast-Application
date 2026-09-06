package com.example.weatherapp

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume



data class WeatherResponse(
    val weather: List<WeatherCondition>,
    val main: MainWeather,
    val wind: Wind,
    val sys: Sys,
    val name: String
)

data class WeatherCondition(val main: String, val description: String, val icon: String)

data class MainWeather(
    val temp: Double,
    @SerializedName("temp_min") val tempMin: Double,
    @SerializedName("temp_max") val tempMax: Double,
    val humidity: Int
)

data class Wind(val speed: Double)
data class Sys(val sunrise: Long, val sunset: Long)
data class ForecastResponse(val list: List<ForecastItem>)

data class ForecastItem(
    val dt: Long,
    val main: MainWeather,
    val weather: List<WeatherCondition>,
    @SerializedName("dt_txt") val dtTxt: String
)


data class DailyForecast(
    val date: String,
    val minTemp: Double,
    val maxTemp: Double,
    val icon: String,
    val description: String
)



interface WeatherApiService {
    @GET("weather")
    suspend fun getCurrentWeather(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric"
    ): WeatherResponse

    @GET("forecast")
    suspend fun getForecast(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric"
    ): ForecastResponse
}

object WeatherApi {
    private const val BASE_URL = "https://api.openweathermap.org/data/2.5/"
    val API_KEY: String = BuildConfig.OPEN_WEATHER_API_KEY

    val service: WeatherApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApiService::class.java)
    }
}


@SuppressLint("MissingPermission")
suspend fun getCurrentLocation(context: Context): Pair<Double, Double>? {
    val client = LocationServices.getFusedLocationProviderClient(context)
    return suspendCancellableCoroutine { continuation ->
        client.lastLocation
            .addOnSuccessListener { location ->
                continuation.resume(location?.let { Pair(it.latitude, it.longitude) })
            }
            .addOnFailureListener { continuation.resume(null) }
    }
}


fun aggregateToDailyForecast(response: ForecastResponse): List<DailyForecast> {
    val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val displayFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    val grouped = response.list.groupBy { dateKeyFormat.format(Date(it.dt * 1000L)) }

    return grouped.map { (dateKey, items) ->
        val representative = items.minByOrNull {
            val hour = it.dtTxt.substring(11, 13).toIntOrNull() ?: 12
            kotlin.math.abs(hour - 12)
        } ?: items.first()

        DailyForecast(
            date = try {
                displayFormat.format(dateKeyFormat.parse(dateKey)!!)
            } catch (e: Exception) {
                dateKey
            },
            minTemp = items.minOf { it.main.tempMin },
            maxTemp = items.maxOf { it.main.tempMax },
            icon = representative.weather.firstOrNull()?.icon ?: "01d",
            description = representative.weather.firstOrNull()?.description ?: ""
        )
    }.sortedBy { it.date }
}
