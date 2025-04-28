package com.example.mapboxdemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.mapboxdemo.databinding.ActivityMainBinding
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.search.MapboxSearchSdk
import com.mapbox.search.ResponseInfo
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.SearchOptions
import com.mapbox.search.SearchSelectionCallback
import com.mapbox.search.SearchSuggestionsCallback
import com.mapbox.search.common.CompletionCallback
import com.mapbox.search.record.IndexableRecord
import com.mapbox.search.result.SearchResult
import com.mapbox.search.result.SearchResultType
import com.mapbox.search.result.SearchSuggestion
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private val MAPBOX_ACCESS_TOKEN = "" // Will be inserted manually later
    private val LOCATION_PERMISSION_REQUEST_CODE = 123
    
    private lateinit var pointAnnotationManager: PointAnnotationManager
    private lateinit var searchEngine: SearchEngine
    private var currentSearchRequestTask: CompletionCallback<List<SearchSuggestion>>? = null
    private var currentUserLocation: Point? = null
    private var currentSearchSuggestions: List<SearchSuggestion> = emptyList()

    // Listeners for location updates
    private val onIndicatorBearingChangedListener = OnIndicatorBearingChangedListener {
        mapView.getMapboxMap().setCamera(CameraOptions.Builder().bearing(it).build())
    }

    private val onIndicatorPositionChangedListener = OnIndicatorPositionChangedListener {
        mapView.getMapboxMap().setCamera(CameraOptions.Builder().center(it).build())
        mapView.gestures.focalPoint = mapView.getMapboxMap().pixelForCoordinate(it)
        currentUserLocation = it
    }

    private val onMoveListener = object : OnMoveListener {
        override fun onMoveBegin(detector: MoveGestureDetector) {
            // When user starts moving the map, stop following the location
            onCameraTrackingDismissed()
        }

        override fun onMove(detector: MoveGestureDetector): Boolean {
            return false
        }

        override fun onMoveEnd(detector: MoveGestureDetector) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mapView = binding.mapView
        
        // Initialize search engine with the latest SDK
        searchEngine = MapboxSearchSdk.createSearchEngine(
            SearchEngineSettings(MAPBOX_ACCESS_TOKEN)
        )
        
        // Initialize annotation manager for search results
        pointAnnotationManager = mapView.annotations.createPointAnnotationManager()
        
        // Set up UI controls
        setupUIControls()

        // Check for location permission
        if (hasLocationPermission()) {
            initializeMap()
        } else {
            requestLocationPermission()
        }
    }
    
    private fun setupUIControls() {
        // Set up zoom in button
        binding.zoomInButton.setOnClickListener {
            val currentZoom = mapView.getMapboxMap().cameraState.zoom
            mapView.getMapboxMap().setCamera(
                CameraOptions.Builder()
                    .zoom(currentZoom + 1.0)
                    .build()
            )
        }
        
        // Set up zoom out button
        binding.zoomOutButton.setOnClickListener {
            val currentZoom = mapView.getMapboxMap().cameraState.zoom
            mapView.getMapboxMap().setCamera(
                CameraOptions.Builder()
                    .zoom(currentZoom - 1.0)
                    .build()
            )
        }
        
        // Set up location button
        binding.locationButton.setOnClickListener {
            currentUserLocation?.let { location ->
                mapView.getMapboxMap().setCamera(
                    CameraOptions.Builder()
                        .center(location)
                        .zoom(15.0)
                        .build()
                )
                // Re-enable location tracking
                mapView.location.addOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
                mapView.location.addOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
                mapView.gestures.addOnMoveListener(onMoveListener)
            }
        }
        
        // Set up search functionality
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { searchQuery ->
                    if (searchQuery.isNotEmpty()) {
                        performSearch(searchQuery)
                    }
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Implement autocomplete suggestions as the user types
                newText?.let { query ->
                    if (query.length >= 3) {
                        // Get search area (user location or map center)
                        val searchCenter = currentUserLocation ?: mapView.getMapboxMap().cameraState.center
                        
                        // Create search options for suggestions
                        val options = SearchOptions.Builder()
                            .limit(5)
                            .proximity(searchCenter)
                            .types(listOf(SearchResultType.POI, SearchResultType.ADDRESS))
                            .languages(listOf(Locale("ja")))
                            .build()
                        
                        // Get suggestions as the user types
                        currentSearchRequestTask = searchEngine.suggestions(
                            query,
                            options,
                            object : SearchSuggestionsCallback {
                                override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                                    // Store suggestions for later use
                                    currentSearchSuggestions = suggestions
                                    
                                    // Here you could display suggestions in a dropdown
                                    // For this implementation, we'll just log them
                                    if (suggestions.isNotEmpty()) {
                                        Log.d("MapboxSearch", "Found ${suggestions.size} suggestions")
                                    }
                                }

                                override fun onError(e: Exception) {
                                    Log.e("MapboxSearch", "Error getting suggestions: ${e.message}")
                                }
                            }
                        )
                    }
                }
                return true
            }
        })
    }

    private fun initializeMap() {
        // Set locale to Japanese for map labels
        val styleUri = Style.MAPBOX_STREETS
        val styleUriWithLocale = "$styleUri?language=ja"
        
        // Load the map with Japanese labels
        mapView.getMapboxMap().loadStyleUri(styleUriWithLocale) { style ->
            // After style is loaded, set the comic style with Japanese labels
            mapView.getMapboxMap().loadStyleUri("mapbox://styles/mapbox/comic?language=ja") { comicStyle ->
                // Enable location component
                enableLocationComponent()
            }
        }
    }
    
    private fun performSearch(query: String) {
        // Cancel any ongoing search
        currentSearchRequestTask?.cancel()
        
        // Clear previous markers
        pointAnnotationManager.deleteAll()
        
        // Get search area (user location or map center)
        val searchCenter = currentUserLocation ?: mapView.getMapboxMap().cameraState.center
        
        // Create search options
        val options = SearchOptions.Builder()
            .limit(5)  // Limit results to 5
            .proximity(searchCenter)  // Search around this location
            .types(listOf(SearchResultType.POI, SearchResultType.ADDRESS))
            .languages(listOf(Locale("ja")))  // Prefer Japanese results
            .build()
        
        // First get suggestions using the new suggest API
        currentSearchRequestTask = searchEngine.suggestions(
            query,
            options,
            object : SearchSuggestionsCallback {
                override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                    // Store suggestions for later use
                    currentSearchSuggestions = suggestions
                    
                    if (suggestions.isNotEmpty()) {
                        // Select the first suggestion to get detailed results
                        selectSuggestion(suggestions.first())
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "No search results found",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onError(e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Search error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
    
    private fun selectSuggestion(suggestion: SearchSuggestion) {
        searchEngine.select(suggestion, object : SearchSelectionCallback {
            override fun onResult(suggestion: SearchSuggestion, result: SearchResult, responseInfo: ResponseInfo) {
                // Add marker for the selected result
                addMarkerForSearchResult(result)
                
                // Move camera to show the result
                result.coordinate?.let { coordinate ->
                    mapView.getMapboxMap().setCamera(
                        CameraOptions.Builder()
                            .center(Point.fromLngLat(coordinate.longitude(), coordinate.latitude()))
                            .zoom(14.0)
                            .build()
                    )
                }
            }
            
            override fun onResults(
                suggestion: SearchSuggestion,
                results: List<SearchResult>,
                responseInfo: ResponseInfo
            ) {
                // Add markers for each result
                for (result in results) {
                    addMarkerForSearchResult(result)
                }
                
                // If we have results, move camera to show the first one
                if (results.isNotEmpty()) {
                    val firstResult = results.first()
                    firstResult.coordinate?.let { coordinate ->
                        mapView.getMapboxMap().setCamera(
                            CameraOptions.Builder()
                                .center(Point.fromLngLat(coordinate.longitude(), coordinate.latitude()))
                                .zoom(14.0)
                                .build()
                        )
                    }
                }
            }

            override fun onError(e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Search error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }
    
    private fun addMarkerForSearchResult(result: SearchResult) {
        result.coordinate?.let { coordinate ->
            val point = Point.fromLngLat(coordinate.longitude(), coordinate.latitude())
            
            // Create a point annotation
            val pointAnnotationOptions = PointAnnotationOptions()
                .withPoint(point)
                .withIconImage(ContextCompat.getDrawable(this, R.drawable.ic_my_location)!!.toBitmap())
            
            // Add the annotation to the map
            pointAnnotationManager.create(pointAnnotationOptions)
        }
    }

    private fun enableLocationComponent() {
        val locationComponentPlugin = mapView.location
        locationComponentPlugin.updateSettings {
            this.enabled = true
            this.locationPuck = LocationPuck2D(
                bearingImage = ContextCompat.getDrawable(
                    this@MainActivity,
                    R.drawable.mapbox_user_puck_icon
                )
            )
        }
        
        // Add listeners for location updates
        locationComponentPlugin.addOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
        locationComponentPlugin.addOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
        mapView.gestures.addOnMoveListener(onMoveListener)
    }

    private fun onCameraTrackingDismissed() {
        // When user interacts with the map, remove the bearing listener
        mapView.location.removeOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
        mapView.gestures.removeOnMoveListener(onMoveListener)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, initialize the map
                initializeMap()
            } else {
                // Permission denied
                Toast.makeText(
                    this,
                    "Location permission is required to show your location on the map",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up location component listeners
        mapView.location.removeOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
        mapView.location.removeOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
        mapView.gestures.removeOnMoveListener(onMoveListener)
        // Cancel any ongoing search
        currentSearchRequestTask?.cancel()
        mapView.onDestroy()
    }
}
