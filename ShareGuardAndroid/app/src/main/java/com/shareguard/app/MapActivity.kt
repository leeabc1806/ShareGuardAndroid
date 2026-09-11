package com.shareguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

class MapActivity : Activity() {
    private data class MapItem(
        val id: Int,
        val title: String,
        val address: String,
        val latitude: Double,
        val longitude: Double
    )

    private data class ItemDistance(val item: MapItem, val meters: Int)

    private data class DistanceOption(val label: String, val meters: Int, val zoomLevel: Int)

    private enum class LocationAction {
        MOVE,
        NEAREST_ITEMS,
        FILTER_BY_DISTANCE
    }

    private var mapView: MapView? = null
    private var kakaoMap: KakaoMap? = null
    private var currentLocationLabel: Label? = null
    private val itemLabels = mutableListOf<Label>()
    private var coordinateText: TextView? = null
    private var browseStatusText: TextView? = null
    private var regionSearchInput: EditText? = null
    private var distanceSpinner: Spinner? = null
    private var selectedPosition = LatLng.from(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
    private var returningSelectedLocation = false
    private var pendingSelectedPosition: LatLng? = null
    private var addressLookupTimeout: Runnable? = null
    private var mapItems: List<MapItem> = emptyList()
    private var lastResolvedLocation: Location? = null
    private var visibleDistances: Map<Int, Int> = emptyMap()
    private var pendingLocationAction = LocationAction.MOVE
    private var pendingRadius = DISTANCE_OPTIONS[DEFAULT_DISTANCE_INDEX]
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cancellationSignals = mutableListOf<CancellationSignal>()
    private val legacyLocationListeners = mutableListOf<LocationListener>()
    private var locating = false
    private var pendingLocationProviders = 0
    private var cachedLocation: Location? = null

    private val locationManager: LocationManager by lazy {
        getSystemService(LocationManager::class.java)
    }

    private val locationTimeout = Runnable {
        if (!locating) return@Runnable
        val fallback = cachedLocation
        val action = pendingLocationAction
        stopLocationRequests()
        if (fallback != null) {
            showCurrentLocation(fallback, cached = true, action = action)
        } else {
            Toast.makeText(
                this,
                "현재 위치를 가져오지 못했습니다. 잠시 후 다시 시도해주세요.",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingLocationAction = LocationAction.MOVE
    }

    private val pickerMode: Boolean
        get() = intent.getStringExtra(EXTRA_MODE) == MODE_PICK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(244, 247, 250)

        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isBlank()) {
            showMissingKeyMessage()
            return
        }

        selectedPosition = initialPosition()
        if (!pickerMode) {
            mapItems = readMapItems()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 247, 250))
        }
        val mapContainer = FrameLayout(this)
        val kakaoMapView = MapView(this)
        mapView = kakaoMapView
        mapContainer.addView(
            kakaoMapView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        if (pickerMode) {
            mapContainer.addView(centerMarker())
        }
        root.addView(mapContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(if (pickerMode) pickerPanel() else browsePanel())
        setContentView(root)

        kakaoMapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() = Unit

                override fun onMapError(error: Exception) {
                    Toast.makeText(
                        this@MapActivity,
                        "카카오맵 실행 실패: ${error.message ?: "인증 설정을 확인하세요."}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            object : KakaoMapReadyCallback() {
                override fun getPosition(): LatLng = selectedPosition

                override fun getZoomLevel(): Int = if (pickerMode) 16 else 13

                override fun onMapReady(kakaoMap: KakaoMap) {
                    this@MapActivity.kakaoMap = kakaoMap
                    if (pickerMode) {
                        observeCenterPosition(kakaoMap)
                    } else {
                        configureBrowseMap(kakaoMap)
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        mapView?.resume()
    }

    override fun onPause() {
        mapView?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        stopLocationRequests()
        addressLookupTimeout?.let(mainHandler::removeCallbacks)
        addressLookupTimeout = null
        kakaoMap = null
        currentLocationLabel = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST) return

        if (hasLocationPermission()) {
            requestCurrentLocation()
        } else {
            Toast.makeText(
                this,
                "내 위치를 확인하려면 위치 권한을 허용해주세요.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun initialPosition(): LatLng {
        if (pickerMode) {
            val latitude = intent.getDoubleExtra(EXTRA_INITIAL_LATITUDE, DEFAULT_LATITUDE)
            val longitude = intent.getDoubleExtra(EXTRA_INITIAL_LONGITUDE, DEFAULT_LONGITUDE)
            return LatLng.from(latitude, longitude)
        }

        val latitudes = intent.getDoubleArrayExtra(EXTRA_LATITUDES) ?: doubleArrayOf()
        val longitudes = intent.getDoubleArrayExtra(EXTRA_LONGITUDES) ?: doubleArrayOf()
        return if (latitudes.isNotEmpty() && longitudes.isNotEmpty()) {
            LatLng.from(latitudes[0], longitudes[0])
        } else {
            LatLng.from(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
        }
    }

    private fun observeCenterPosition(kakaoMap: KakaoMap) {
        updateCoordinateText()
        kakaoMap.setOnCameraMoveEndListener { _, cameraPosition, _ ->
            selectedPosition = cameraPosition.position
            updateCoordinateText()
        }
    }

    private fun readMapItems(): List<MapItem> {
        val ids = intent.getIntArrayExtra(EXTRA_ITEM_IDS) ?: intArrayOf()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        val addresses = intent.getStringArrayListExtra(EXTRA_ADDRESSES) ?: arrayListOf()
        val latitudes = intent.getDoubleArrayExtra(EXTRA_LATITUDES) ?: doubleArrayOf()
        val longitudes = intent.getDoubleArrayExtra(EXTRA_LONGITUDES) ?: doubleArrayOf()
        val count = min(min(ids.size, titles.size), min(latitudes.size, longitudes.size))

        return List(count) { index ->
            MapItem(
                id = ids[index],
                title = titles[index],
                address = addresses.getOrNull(index).orEmpty(),
                latitude = latitudes[index],
                longitude = longitudes[index]
            )
        }
    }

    private fun configureBrowseMap(kakaoMap: KakaoMap) {
        kakaoMap.setOnLabelClickListener { _, _, label ->
            val itemId = label.tag as? Int ?: return@setOnLabelClickListener true
            val item = mapItems.find { it.id == itemId }
            val description = if (item != null) {
                buildList {
                    add(item.title)
                    if (item.address.isNotBlank()) add(item.address)
                    visibleDistances[itemId]?.let { add("내 위치에서 ${formatDistance(it)}") }
                }.joinToString("\n")
            } else {
                "선택한 물품"
            }
            Toast.makeText(this, description, Toast.LENGTH_SHORT).show()
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_SELECTED_ITEM_ID, itemId)
            )
            finish()
            true
        }

        showAllItems()
    }

    private fun refreshItemMarkers(items: List<MapItem>, status: String) {
        val map = kakaoMap ?: return
        itemLabels.forEach(Label::remove)
        itemLabels.clear()

        val marker = markerBitmap()
        val layer = map.labelManager?.layer ?: return

        items.forEach { item ->
            itemLabels += layer.addLabel(
                LabelOptions.from(LatLng.from(item.latitude, item.longitude))
                    .setStyles(marker)
                    .setClickable(true)
                    .setTag(item.id)
            )
        }
        browseStatusText?.text = status
    }

    private fun showAllItems() {
        visibleDistances = emptyMap()
        refreshItemMarkers(mapItems, "전체 물품 ${mapItems.size}개를 표시하고 있습니다.")
    }

    private fun searchNearestItems() {
        requestLocation(LocationAction.NEAREST_ITEMS)
    }

    private fun searchByDistance() {
        pendingRadius = DISTANCE_OPTIONS[
            distanceSpinner?.selectedItemPosition?.coerceIn(DISTANCE_OPTIONS.indices)
                ?: DEFAULT_DISTANCE_INDEX
        ]
        requestLocation(LocationAction.FILTER_BY_DISTANCE)
    }

    private fun searchByRegion() {
        val query = regionSearchInput?.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            Toast.makeText(this, "검색할 시/군/구 또는 행정동을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val matches = mapItems.filter { regionMatches(it.address, query) }
        visibleDistances = distancesFromLastLocation(matches)
        refreshItemMarkers(matches, "‘$query’ 지역 검색 결과 ${matches.size}개")
        hideKeyboard()

        matches.firstOrNull()?.let { first ->
            kakaoMap?.moveCamera(
                CameraUpdateFactory.newCenterPosition(
                    LatLng.from(first.latitude, first.longitude),
                    REGION_SEARCH_ZOOM_LEVEL
                )
            )
        }
        if (matches.isEmpty()) {
            Toast.makeText(this, "입력한 지역에 등록된 물품이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickerPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
        setBackgroundColor(Color.WHITE)
        addView(label("지도를 움직여 거래 위치를 가운데에 맞추세요.", 15, true))
        addView(
            currentLocationButton(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(8)
            }
        )
        coordinateText = label("", 13, false).apply {
            setTextColor(Color.rgb(100, 112, 132))
        }
        addView(coordinateText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        addView(Button(this@MapActivity).apply {
            text = "이 위치를 거래 장소로 선택"
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.rgb(37, 99, 235), 8)
            setOnClickListener {
                returnSelectedLocation(this)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        updateCoordinateText()
    }

    private fun returnSelectedLocation(button: Button) {
        if (returningSelectedLocation) return
        returningSelectedLocation = true
        button.isEnabled = false
        button.text = "선택 위치 주소 확인 중..."
        pendingSelectedPosition = selectedPosition

        if (!Geocoder.isPresent()) {
            finishLocationSelection(null)
            return
        }

        val position = pendingSelectedPosition ?: selectedPosition
        val geocoder = Geocoder(this, Locale.KOREA)
        addressLookupTimeout = Runnable { finishLocationSelection(null) }.also {
            mainHandler.postDelayed(it, ADDRESS_LOOKUP_TIMEOUT_MILLIS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                geocoder.getFromLocation(
                    position.latitude,
                    position.longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            runOnUiThread {
                                finishLocationSelection(addresses.firstOrNull()?.displayAddress())
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            runOnUiThread { finishLocationSelection(null) }
                        }
                    }
                )
            } catch (_: IllegalArgumentException) {
                finishLocationSelection(null)
            }
        } else {
            Thread {
                @Suppress("DEPRECATION")
                val address = runCatching {
                    geocoder.getFromLocation(position.latitude, position.longitude, 1)
                        ?.firstOrNull()
                        ?.displayAddress()
                }.getOrNull()
                runOnUiThread { finishLocationSelection(address) }
            }.start()
        }
    }

    private fun finishLocationSelection(address: String?) {
        if (!returningSelectedLocation) return
        returningSelectedLocation = false
        addressLookupTimeout?.let(mainHandler::removeCallbacks)
        addressLookupTimeout = null
        val position = pendingSelectedPosition ?: selectedPosition
        pendingSelectedPosition = null

        val fallbackAddress = String.format(
            Locale.US,
            "지도 선택 위치 (%.6f, %.6f)",
            position.latitude,
            position.longitude
        )
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_SELECTED_LATITUDE, position.latitude)
                .putExtra(EXTRA_SELECTED_LONGITUDE, position.longitude)
                .putExtra(EXTRA_SELECTED_ADDRESS, address?.takeIf(String::isNotBlank) ?: fallbackAddress)
        )
        finish()
    }

    private fun Address.displayAddress(): String? {
        val addressLine = if (maxAddressLineIndex >= 0) getAddressLine(0) else null
        if (!addressLine.isNullOrBlank()) {
            return addressLine
                .removePrefix("대한민국 ")
                .removePrefix("대한민국")
                .trim()
        }

        return listOfNotNull(adminArea, subAdminArea, locality, subLocality, thoroughfare, featureName)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
            .takeIf(String::isNotBlank)
    }

    private fun browsePanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(14))
        setBackgroundColor(Color.WHITE)
        addView(label("물품 위치 검색", 15, true))
        browseStatusText = label("지도에서 물품을 불러오는 중입니다.", 12, false).apply {
            setTextColor(Color.rgb(100, 112, 132))
        }
        addView(browseStatusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })
        addView(
            actionButton("① 내 위치 기준 가까운 물품") { searchNearestItems() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(8)
            }
        )

        val distanceRow = LinearLayout(this@MapActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        distanceSpinner = Spinner(this@MapActivity).apply {
            adapter = ArrayAdapter(
                this@MapActivity,
                android.R.layout.simple_spinner_dropdown_item,
                DISTANCE_OPTIONS.map(DistanceOption::label)
            )
            setSelection(DEFAULT_DISTANCE_INDEX)
            background = rounded(Color.rgb(248, 250, 252), 8, Color.rgb(203, 213, 225))
        }
        distanceRow.addView(distanceSpinner, LinearLayout.LayoutParams(0, dp(44), 0.38f).apply {
            rightMargin = dp(8)
        })
        distanceRow.addView(
            actionButton("② 선택 거리 안 물품") { searchByDistance() },
            LinearLayout.LayoutParams(0, dp(44), 0.62f)
        )
        addView(distanceRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(7)
        })

        val regionRow = LinearLayout(this@MapActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        regionSearchInput = EditText(this@MapActivity).apply {
            hint = "③ 시/군/구·행정동"
            textSize = 13f
            setSingleLine(true)
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(Color.WHITE, 8, Color.rgb(203, 213, 225))
        }
        regionRow.addView(regionSearchInput, LinearLayout.LayoutParams(0, dp(44), 0.62f).apply {
            rightMargin = dp(8)
        })
        regionRow.addView(
            secondaryButton("지역 검색") { searchByRegion() },
            LinearLayout.LayoutParams(0, dp(44), 0.38f)
        )
        addView(regionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(7)
        })

        val utilityRow = LinearLayout(this@MapActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        utilityRow.addView(
            secondaryButton("현재 위치 표시") { moveToCurrentLocation() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) }
        )
        utilityRow.addView(
            secondaryButton("검색 초기화") {
                regionSearchInput?.text?.clear()
                showAllItems()
            },
            LinearLayout.LayoutParams(0, dp(40), 1f)
        )
        addView(utilityRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
            topMargin = dp(7)
        })
    }

    private fun currentLocationButton(): Button = Button(this).apply {
        text = "내 위치로 이동"
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(37, 99, 235))
        background = rounded(Color.rgb(219, 234, 254), 8)
        setOnClickListener { moveToCurrentLocation() }
    }

    private fun moveToCurrentLocation() {
        requestLocation(LocationAction.MOVE)
    }

    private fun requestLocation(action: LocationAction) {
        if (kakaoMap == null) {
            Toast.makeText(this, "지도가 준비된 뒤 다시 눌러주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        pendingLocationAction = action
        if (!hasLocationPermission()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }

        requestCurrentLocation()
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "내 위치를 확인하려면 위치 권한을 허용해주세요.", Toast.LENGTH_LONG).show()
            return
        }

        if (locating) {
            Toast.makeText(this, "현재 위치를 확인하고 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val enabledProviders = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        ).filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }

        if (enabledProviders.isEmpty()) {
            Toast.makeText(
                this,
                "휴대폰의 위치 기능을 켠 뒤 다시 시도해주세요.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        cachedLocation = enabledProviders
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter(::isRecentLocation)
            .maxByOrNull(Location::getTime)

        locating = true
        pendingLocationProviders = enabledProviders.size
        Toast.makeText(this, "현재 위치를 확인하고 있습니다.", Toast.LENGTH_SHORT).show()
        mainHandler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MILLIS)

        enabledProviders.forEach(::requestLocationFromProvider)
    }

    private fun requestLocationFromProvider(provider: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancellationSignal = CancellationSignal()
                cancellationSignals += cancellationSignal
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    mainExecutor
                ) { location -> onLocationResult(location) }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(this)
                        legacyLocationListeners.remove(this)
                        onLocationResult(location)
                    }

                    override fun onProviderDisabled(provider: String) {
                        locationManager.removeUpdates(this)
                        legacyLocationListeners.remove(this)
                        onLocationResult(null)
                    }
                }
                legacyLocationListeners += listener
                @Suppress("DEPRECATION")
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            onLocationResult(null)
        } catch (_: IllegalArgumentException) {
            onLocationResult(null)
        }
    }

    private fun onLocationResult(location: Location?) {
        if (!locating) return

        if (location != null) {
            val action = pendingLocationAction
            stopLocationRequests()
            showCurrentLocation(location, cached = false, action = action)
            pendingLocationAction = LocationAction.MOVE
            return
        }

        pendingLocationProviders -= 1
        if (pendingLocationProviders > 0) return

        val fallback = cachedLocation
        val action = pendingLocationAction
        stopLocationRequests()
        if (fallback != null) {
            showCurrentLocation(fallback, cached = true, action = action)
        } else {
            Toast.makeText(
                this,
                "현재 위치를 가져오지 못했습니다. 잠시 후 다시 시도해주세요.",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingLocationAction = LocationAction.MOVE
    }

    private fun stopLocationRequests() {
        locating = false
        pendingLocationProviders = 0
        mainHandler.removeCallbacks(locationTimeout)
        cancellationSignals.forEach(CancellationSignal::cancel)
        cancellationSignals.clear()
        legacyLocationListeners.forEach { listener ->
            runCatching { locationManager.removeUpdates(listener) }
        }
        legacyLocationListeners.clear()
    }

    private fun showCurrentLocation(location: Location, cached: Boolean, action: LocationAction) {
        val map = kakaoMap ?: return
        val position = LatLng.from(location.latitude, location.longitude)
        lastResolvedLocation = location

        if (pickerMode) {
            selectedPosition = position
            updateCoordinateText()
        }

        val label = currentLocationLabel
        if (label == null) {
            currentLocationLabel = map.labelManager?.layer?.addLabel(
                LabelOptions.from(position)
                    .setStyles(currentLocationBitmap())
                    .setClickable(false)
            )
        } else {
            label.moveTo(position)
        }

        map.moveCamera(
            CameraUpdateFactory.newCenterPosition(position, CURRENT_LOCATION_ZOOM_LEVEL)
        )

        if (!pickerMode) {
            when (action) {
                LocationAction.MOVE -> Unit
                LocationAction.NEAREST_ITEMS -> showNearestItems(location)
                LocationAction.FILTER_BY_DISTANCE -> showItemsWithinDistance(location, pendingRadius)
            }
        }

        val accuracy = if (location.hasAccuracy()) {
            " · 정확도 약 ${location.accuracy.roundToInt()}m"
        } else {
            ""
        }
        val source = if (cached) "최근 위치" else "현재 위치"
        Toast.makeText(this, "${source}로 이동했습니다$accuracy", Toast.LENGTH_SHORT).show()
    }

    private fun showNearestItems(location: Location) {
        val nearest = mapItems
            .map { ItemDistance(it, distanceMeters(location, it)) }
            .sortedBy(ItemDistance::meters)
        visibleDistances = nearest.associate { it.item.id to it.meters }
        val preview = nearest.take(2).joinToString(" · ") {
            "${it.item.title.take(9)} ${formatDistance(it.meters)}"
        }
        val status = if (nearest.isEmpty()) {
            "내 위치 기준으로 검색된 물품이 없습니다."
        } else {
            "내 위치 기준 가까운 순 ${nearest.size}개 · $preview"
        }
        refreshItemMarkers(nearest.map(ItemDistance::item), status)
    }

    private fun showItemsWithinDistance(location: Location, option: DistanceOption) {
        val matches = mapItems
            .map { ItemDistance(it, distanceMeters(location, it)) }
            .filter { it.meters <= option.meters }
            .sortedBy(ItemDistance::meters)
        visibleDistances = matches.associate { it.item.id to it.meters }
        refreshItemMarkers(
            matches.map(ItemDistance::item),
            "내 위치 ${option.label} 이내 검색 결과 ${matches.size}개"
        )
        kakaoMap?.moveCamera(
            CameraUpdateFactory.newCenterPosition(
                LatLng.from(location.latitude, location.longitude),
                option.zoomLevel
            )
        )
        if (matches.isEmpty()) {
            Toast.makeText(this, "선택한 거리 안에 등록된 물품이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun distanceMeters(location: Location, item: MapItem): Int {
        val result = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            item.latitude,
            item.longitude,
            result
        )
        return result[0].roundToInt()
    }

    private fun distancesFromLastLocation(items: List<MapItem>): Map<Int, Int> {
        val location = lastResolvedLocation ?: return emptyMap()
        return items.associate { it.id to distanceMeters(location, it) }
    }

    private fun regionMatches(address: String, query: String): Boolean {
        val normalizedAddress = normalizeRegion(address)
        val normalizedQuery = normalizeRegion(query)
        if (normalizedQuery.isBlank()) return false
        if (normalizedAddress.contains(normalizedQuery)) return true

        val tokens = query.split(Regex("[\\s,]+"))
            .map(::normalizeRegion)
            .filter { it.length >= 2 }
        return tokens.isNotEmpty() && tokens.all(normalizedAddress::contains)
    }

    private fun normalizeRegion(value: String): String = value
        .lowercase(Locale.KOREA)
        .replace(Regex("[\\s,·-]"), "")

    private fun formatDistance(meters: Int): String = when {
        meters < 1_000 -> "${meters}m"
        else -> String.format(Locale.KOREA, "%.1fkm", meters / 1_000.0)
    }

    private fun hideKeyboard() {
        val input = regionSearchInput ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun isRecentLocation(location: Location): Boolean {
        val ageNanos = android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return ageNanos in 0..TimeUnit.MINUTES.toNanos(MAX_CACHED_LOCATION_AGE_MINUTES)
    }

    private fun centerMarker(): View = TextView(this).apply {
        text = "●"
        textSize = 32f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(37, 99, 235))
        setShadowLayer(4f, 0f, 2f, Color.WHITE)
        layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER).apply {
            bottomMargin = dp(18)
        }
    }

    private fun markerBitmap(): Bitmap {
        val width = dp(34)
        val height = dp(44)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 99, 235) }
        val radius = width * 0.38f
        canvas.drawCircle(width / 2f, radius + dp(2), radius, paint)
        canvas.drawPath(Path().apply {
            moveTo(width / 2f - radius * 0.55f, radius * 1.55f)
            lineTo(width / 2f + radius * 0.55f, radius * 1.55f)
            lineTo(width / 2f, height.toFloat())
            close()
        }, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(width / 2f, radius + dp(2), radius * 0.36f, paint)
        return bitmap
    }

    private fun currentLocationBitmap(): Bitmap {
        val size = dp(36)
        val center = size / 2f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.argb(70, 37, 99, 235)
        canvas.drawCircle(center, center, dp(17).toFloat(), paint)
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, dp(10).toFloat(), paint)
        paint.color = Color.rgb(37, 99, 235)
        canvas.drawCircle(center, center, dp(7).toFloat(), paint)
        return bitmap
    }

    private fun updateCoordinateText() {
        coordinateText?.text = String.format(
            Locale.US,
            "위도 %.6f  ·  경도 %.6f",
            selectedPosition.latitude,
            selectedPosition.longitude
        )
    }

    private fun showMissingKeyMessage() {
        setContentView(label(
            "카카오맵 API 키가 설정되지 않았습니다.\n\n" +
                "ShareGuardAndroid/local.properties에\n" +
                "KAKAO_NATIVE_APP_KEY=발급받은_네이티브_앱_키\n" +
                "형식으로 추가한 뒤 앱을 다시 빌드하세요.",
            16,
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(244, 247, 250))
        })
    }

    private fun label(value: String, size: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.rgb(24, 33, 47))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionButton(value: String, onClick: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(37, 99, 235), 8)
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(value: String, onClick: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(37, 99, 235))
        background = rounded(Color.rgb(239, 246, 255), 8, Color.rgb(191, 219, 254))
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_MODE = "map_mode"
        const val EXTRA_ITEM_IDS = "map_item_ids"
        const val EXTRA_TITLES = "map_titles"
        const val EXTRA_ADDRESSES = "map_addresses"
        const val EXTRA_LATITUDES = "map_latitudes"
        const val EXTRA_LONGITUDES = "map_longitudes"
        const val EXTRA_INITIAL_LATITUDE = "map_initial_latitude"
        const val EXTRA_INITIAL_LONGITUDE = "map_initial_longitude"
        const val EXTRA_SELECTED_ITEM_ID = "map_selected_item_id"
        const val EXTRA_SELECTED_LATITUDE = "map_selected_latitude"
        const val EXTRA_SELECTED_LONGITUDE = "map_selected_longitude"
        const val EXTRA_SELECTED_ADDRESS = "map_selected_address"
        const val MODE_BROWSE = "browse"
        const val MODE_PICK = "pick"

        private const val LOCATION_PERMISSION_REQUEST = 2101
        private const val LOCATION_TIMEOUT_MILLIS = 12_000L
        private const val ADDRESS_LOOKUP_TIMEOUT_MILLIS = 8_000L
        private const val MAX_CACHED_LOCATION_AGE_MINUTES = 10L
        private const val CURRENT_LOCATION_ZOOM_LEVEL = 16
        private const val REGION_SEARCH_ZOOM_LEVEL = 14
        private const val DEFAULT_LATITUDE = 37.4979
        private const val DEFAULT_LONGITUDE = 127.0276
        private const val DEFAULT_DISTANCE_INDEX = 2
        private val DISTANCE_OPTIONS = listOf(
            DistanceOption("500m", 500, 16),
            DistanceOption("1km", 1_000, 15),
            DistanceOption("3km", 3_000, 14),
            DistanceOption("5km", 5_000, 13),
            DistanceOption("10km", 10_000, 12),
            DistanceOption("20km", 20_000, 11)
        )
    }
}
