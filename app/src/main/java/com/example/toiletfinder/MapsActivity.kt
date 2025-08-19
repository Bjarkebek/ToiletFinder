package com.example.toiletfinder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.SerializedName
import java.io.BufferedReader
import java.io.InputStreamReader

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.gms.maps.model.Marker

class CustomInfoWindowAdapter(private val context: Context) : GoogleMap.InfoWindowAdapter {

    private val window: View = LayoutInflater.from(context).inflate(R.layout.custom_info_window, null)

    private fun setInfoWindowText(marker: Marker, view: View) {
        val title = marker.title
        val snippetParts = marker.snippet?.split("\n") // Assuming you still use \n to separate

        val tvTitle = view.findViewById<TextView>(R.id.info_window_title)
        val tvSnippetLine1 = view.findViewById<TextView>(R.id.info_window_snippet_line1)
        val tvSnippetLine2 = view.findViewById<TextView>(R.id.info_window_snippet_line2)

        tvTitle.text = title

        if (snippetParts != null && snippetParts.isNotEmpty()) {
            tvSnippetLine1.text = snippetParts.getOrNull(0) ?: ""
            tvSnippetLine1.visibility = if (tvSnippetLine1.text.isNotEmpty()) View.VISIBLE else View.GONE

            tvSnippetLine2.text = snippetParts.getOrNull(1) ?: ""
            tvSnippetLine2.visibility = if (tvSnippetLine2.text.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            tvSnippetLine1.visibility = View.GONE
            tvSnippetLine2.visibility = View.GONE
        }
    }

    // This method is called first. If it returns null, then getInfoContents() is called.
    // Use this if you want to provide a custom frame for the info window.
    override fun getInfoWindow(marker: Marker): View? {
        setInfoWindowText(marker, window)
        return window
    }

    // This method is called if getInfoWindow() returns null.
    // Use this if you only want to customize the contents *inside* the default info window frame.
    override fun getInfoContents(marker: Marker): View? {
        // If you only want to change the content, and keep the default bubble,
        // you might inflate and return your view here, and return null from getInfoWindow.
        // For full control, use getInfoWindow and return null here or don't override.
        return null // Or implement similarly to getInfoWindow if you choose this path
    }
}

data class Toilet(
    val name: String,
    val lat: Double,
    val lng: Double,
    val openingHours: String,
    val handicap: Boolean? = null
)

// 1. Root Object for GeoJSON FeatureCollection
data class ToiletFeatureCollection(
    val type: String,
    val features: List<Feature>
)

// 2. Feature Object (inside the "features" array)
data class Feature(
    val type: String,
    // val id: String, // You might not need this one for the map markers
    val geometry: Geometry,
    // @SerializedName("geometry_name")
    // val geometryName: String, // You might not need this one
    val properties: Properties
)

// 3. Geometry Object (inside each "Feature")
data class Geometry(
    val type: String,
    val coordinates: List<Double> // [longitude, latitude]
)

// 4. Properties Object (inside each "Feature" - this is where your toilet details are)
data class Properties(
    // val id: Int, // You might not need this one
    @SerializedName("toilet_lokalitet")
    val toiletLokalitet: String?, // Make it nullable in case it's missing or empty
    @SerializedName("vejnavn_husnummer")
    val vejnavnHusnummer: String?, // Make it nullable
    // val postnummer: String,
    // @SerializedName("toilet_betegnelse")
    // val toiletBetegnelse: String,
    // @SerializedName("toilettype_design")
    // val toilettypeDesign: String,
    @SerializedName("handicapadgang")
    val handicapAdgang: String?, // "Ja", "Nej", or null
    @SerializedName("aabningstid_doegn")
    val aabningstidDoegn: String,
    // @SerializedName("aabningsperiode")
    // val aabningsperiode: String,
    // val status: String,
    // @SerializedName("grafitti_ansvarlig")
    // val grafittiAnsvarlig: String,
    // val driftsbydel: String,
    // @SerializedName("utm_x")
    // val utmX: Double,
    // @SerializedName("utm_y")
    // val utmY: Double,
    val longitude: Double,
    val latitude: Double
)


class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private val copenhagen = LatLng(55.6758, 12.5683)

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted && ::map.isInitialized) {
            enableMyLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(copenhagen, 13f))

        // Set the custom info window adapter
        map.setInfoWindowAdapter(CustomInfoWindowAdapter(this)) // 'this' is your Activity context

        // 1) Load JSON from assets
        val jsonString = loadJsonFromAssets("toilet_cph.json")
        if (jsonString == null) {
            // Handle error: e.g., show a Toast message to the user
            Log.e("MapsActivity", "Failed to load toilet_cph.json")
            return
        }

        // 2) Parse the GeoJSON structure
        val gson = Gson() // You already have Gson
        val featureCollection: ToiletFeatureCollection? = try {
            gson.fromJson(jsonString, ToiletFeatureCollection::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("MapsActivity", "Error parsing JSON: ${e.message}")
            null // Handle parsing error
        }

        if (featureCollection == null) {
            // Handle error: e.g., show a Toast message
            Log.e("MapsActivity", "Parsed feature collection is null.")
            return
        }

        // 3) Transform the parsed GeoJSON features into your List<Toilet>
        val toilets = mutableListOf<Toilet>()
        featureCollection.features.forEach { feature ->
            val props = feature.properties
            val name = props.toiletLokalitet?.takeIf { it.isNotBlank() }
                ?: props.vejnavnHusnummer?.takeIf { it.isNotBlank() }
                ?: "Ukendt toilet" // Fallback name


            val lat = props.latitude
            val lng = props.longitude
            val isHandicap = props.handicapAdgang?.equals("Ja", ignoreCase = true)
            val openingHoursText = props.aabningstidDoegn

            toilets.add(Toilet(name, lat, lng, openingHoursText, isHandicap))
        }

        // 4) Add markers to the map (using the transformed 'toilets' list)
        toilets.forEach { t ->
            val pos = LatLng(t.lat, t.lng)
            val handicapStatus = if (t.handicap == true) "Handicapvenlig" else "Ikke handicapvenlig"
            map.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title(t.name)
                    // You can still use the snippet to pass data to your adapter.
                    // The adapter can then parse this snippet.
                    .snippet("$handicapStatus\n${t.openingHours}")
            )
        }



        // 5) (Valgfrit) vis brugerens lokation
        askLocationPermissionIfNeeded()
    }

    private fun loadJsonFromAssets(fileName: String): String? =
        try {
            assets.open(fileName).use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    private fun askLocationPermissionIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation()
        } else {
            requestLocationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun enableMyLocation() {
        try {
            map.isMyLocationEnabled = true
        } catch (e: SecurityException) {
            // ignorér hvis permission ikke er givet
        }
    }
}


