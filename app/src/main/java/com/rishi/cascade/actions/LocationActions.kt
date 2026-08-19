package com.rishi.cascade.actions

import android.annotation.SuppressLint
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.rishi.cascade.engine.FlowError
import com.rishi.cascade.model.ActionDef
import com.rishi.cascade.model.Cat
import com.rishi.cascade.model.ParamSpec
import com.rishi.cascade.model.ParamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

object LocationActions {

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(app: android.content.Context, timeoutSec: Int): Location {
        val lm = app.getSystemService(LocationManager::class.java)
            ?: throw FlowError("Location services unavailable")
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)

        val fresh = withTimeoutOrNull(timeoutSec * 1000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val provider = providers.firstOrNull { lm.isProviderEnabled(it) }
                if (provider == null) { cont.resume(null); return@suspendCancellableCoroutine }
                try {
                    val listener = android.location.LocationListener { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (fresh != null) return fresh

        val last = providers.mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null }
        }.maxByOrNull { it.time }
        return last ?: throw FlowError("No location fix. Check that location is on and Cascade has permission.")
    }

    fun all(): List<Action> = listOf(

        act(ActionDef(
            "loc.current", "Current Location", Cat.LOCATION, "MyLocation",
            params = listOf(
                ParamSpec("field", "Get", ParamType.CHOICE, "Coordinates",
                    listOf("Coordinates", "Latitude", "Longitude", "Address", "Accuracy (m)", "Altitude", "Speed", "Everything")),
                ParamSpec("timeout", "Wait up to (seconds)", ParamType.NUMBER, "15")
            ),
            output = "Where you are",
            permissions = listOf("android.permission.ACCESS_FINE_LOCATION"),
            keywords = "location gps where coordinates latitude longitude address position"
        )) { env ->
            val loc = currentLocation(env.app, env.int("timeout", 15))
            suspend fun address(): String = withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    val list = Geocoder(env.app, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                    list?.firstOrNull()?.getAddressLine(0) ?: ""
                } catch (e: Exception) { "" }
            }
            when (env.choice("field", "Coordinates")) {
                "Latitude" -> loc.latitude
                "Longitude" -> loc.longitude
                "Address" -> address()
                "Accuracy (m)" -> loc.accuracy.toDouble()
                "Altitude" -> loc.altitude
                "Speed" -> loc.speed.toDouble()
                "Everything" -> JSONObject()
                    .put("latitude", loc.latitude).put("longitude", loc.longitude)
                    .put("accuracy", loc.accuracy).put("altitude", loc.altitude)
                    .put("speed", loc.speed).put("address", address())
                else -> loc.latitude.toString() + "," + loc.longitude.toString()
            }
        },

        act(ActionDef(
            "loc.geocode", "Look Up Place", Cat.LOCATION, "Search",
            summary = "Find %query%",
            params = listOf(
                ParamSpec("query", "Address or place", ParamType.TEXT, "{{last}}"),
                ParamSpec("field", "Get", ParamType.CHOICE, "Coordinates",
                    listOf("Coordinates", "Full address", "Everything"))
            ),
            output = "The place",
            keywords = "geocode address search place coordinates lookup"
        )) { env ->
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val res = try {
                    Geocoder(env.app, Locale.getDefault()).getFromLocationName(env.str("query"), 1)
                } catch (e: Exception) { null }
                val a = res?.firstOrNull() ?: throw FlowError("Could not find that place")
                when (env.choice("field", "Coordinates")) {
                    "Full address" -> a.getAddressLine(0) ?: ""
                    "Everything" -> JSONObject().put("latitude", a.latitude).put("longitude", a.longitude)
                        .put("address", a.getAddressLine(0) ?: "").put("locality", a.locality ?: "")
                        .put("country", a.countryName ?: "")
                    else -> a.latitude.toString() + "," + a.longitude.toString()
                }
            }
        },

        act(ActionDef(
            "loc.distance", "Distance Between", Cat.LOCATION, "Straighten",
            summary = "%from% to %to%",
            params = listOf(
                ParamSpec("from", "From", ParamType.TEXT, "", hint = "lat,lon - blank means where you are now"),
                ParamSpec("to", "To", ParamType.TEXT, "", hint = "lat,lon"),
                ParamSpec("unit", "Unit", ParamType.CHOICE, "Kilometres", listOf("Metres", "Kilometres", "Miles"))
            ),
            output = "A distance",
            keywords = "distance between far away km miles location"
        )) { env ->
            fun parse(s: String): DoubleArray {
                val p = s.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                if (p.size < 2) throw FlowError("Expected \"latitude,longitude\" but got: " + s)
                return doubleArrayOf(p[0], p[1])
            }
            val from = if (env.str("from").isBlank()) {
                val l = currentLocation(env.app, 15); doubleArrayOf(l.latitude, l.longitude)
            } else parse(env.str("from"))
            val to = parse(env.str("to"))
            val out = FloatArray(1)
            Location.distanceBetween(from[0], from[1], to[0], to[1], out)
            when (env.choice("unit", "Kilometres")) {
                "Metres" -> out[0].toDouble()
                "Miles" -> out[0] / 1609.344
                else -> out[0] / 1000.0
            }
        }
    )
}
