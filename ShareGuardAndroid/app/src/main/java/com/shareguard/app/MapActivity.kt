package com.shareguard.app

import android.Manifest
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    private var mapView: MapView? = null
    private var kakaoMap: KakaoMap? = null
    private var currentLocationLabel: Label? = null
    private var coordinateText: TextView? = null
    private var selectedPosition = LatLng.from(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
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
        stopLocationRequests()
        if (fallback != null) {
            showCurrentLocation(fallback, cached = true)
        } else {
            Toast.makeText(
                this,
                "현재 위치를 가져오지 못했습니다. 잠시 후 다시 시도해주세요.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val pickerMode: Boolean
        get() = intent.getStringExtra(EXTRA_MODE) == MODE_PICK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(244, 247, 250)

        if (!ShareGuardApplication.supportsKakaoMaps()) {
            showUnsupportedDeviceMessage()
            return
        }

        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isBlank()) {
            showMissingKeyMessage()
            return
        }

        selectedPosition = initialPosition()
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
                        addItemMarkers(kakaoMap)
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

    private fun addItemMarkers(kakaoMap: KakaoMap) {
        val ids = intent.getIntArrayExtra(EXTRA_ITEM_IDS) ?: intArrayOf()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        val addresses = intent.getStringArrayListExtra(EXTRA_ADDRESSES) ?: arrayListOf()
        val latitudes = intent.getDoubleArrayExtra(EXTRA_LATITUDES) ?: doubleArrayOf()
        val longitudes = intent.getDoubleArrayExtra(EXTRA_LONGITUDES) ?: doubleArrayOf()
        val count = min(min(ids.size, titles.size), min(latitudes.size, longitudes.size))
        val marker = markerBitmap()
        val layer = kakaoMap.labelManager?.layer ?: return

        repeat(count) { index ->
            layer.addLabel(
                LabelOptions.from(LatLng.from(latitudes[index], longitudes[index]))
                    .setStyles(marker)
                    .setClickable(true)
                    .setTag(ids[index])
            )
        }

        kakaoMap.setOnLabelClickListener { _, _, label ->
            val itemId = label.tag as? Int ?: return@setOnLabelClickListener true
            val index = ids.indexOf(itemId)
            val description = if (index >= 0) {
                val address = addresses.getOrNull(index).orEmpty()
                listOf(titles.getOrNull(index).orEmpty(), address)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
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
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_SELECTED_LATITUDE, selectedPosition.latitude)
                        .putExtra(EXTRA_SELECTED_LONGITUDE, selectedPosition.longitude)
                )
                finish()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        updateCoordinateText()
    }

    private fun browsePanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(14))
        setBackgroundColor(Color.WHITE)
        addView(label("물품 마커를 누르면 해당 물품 상세로 돌아갑니다.", 14, true).apply {
            gravity = Gravity.CENTER
        })
        addView(
            currentLocationButton(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(8)
            }
        )
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
        if (kakaoMap == null) {
            Toast.makeText(this, "지도가 준비된 뒤 다시 눌러주세요.", Toast.LENGTH_SHORT).show()
            return
        }

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

    private fun requestCurrentLocation() {
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
            stopLocationRequests()
            showCurrentLocation(location, cached = false)
            return
        }

        pendingLocationProviders -= 1
        if (pendingLocationProviders > 0) return

        val fallback = cachedLocation
        stopLocationRequests()
        if (fallback != null) {
            showCurrentLocation(fallback, cached = true)
        } else {
            Toast.makeText(
                this,
                "현재 위치를 가져오지 못했습니다. 잠시 후 다시 시도해주세요.",
                Toast.LENGTH_LONG
            ).show()
        }
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

    private fun showCurrentLocation(location: Location, cached: Boolean) {
        val map = kakaoMap ?: return
        val position = LatLng.from(location.latitude, location.longitude)

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

        val accuracy = if (location.hasAccuracy()) {
            " · 정확도 약 ${location.accuracy.roundToInt()}m"
        } else {
            ""
        }
        val source = if (cached) "최근 위치" else "현재 위치"
        Toast.makeText(this, "${source}로 이동했습니다$accuracy", Toast.LENGTH_SHORT).show()
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

    private fun showUnsupportedDeviceMessage() {
        setContentView(label(
            "현재 에뮬레이터는 x86 계열이라 카카오맵을 실행할 수 없습니다.\n\n" +
                "카카오맵 SDK는 arm64-v8a와 armeabi-v7a 기기를 지원합니다.\n" +
                "실제 Android 휴대폰 또는 ARM64 에뮬레이터에서 확인해주세요.",
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

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
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
        const val MODE_BROWSE = "browse"
        const val MODE_PICK = "pick"

        private const val LOCATION_PERMISSION_REQUEST = 2101
        private const val LOCATION_TIMEOUT_MILLIS = 12_000L
        private const val MAX_CACHED_LOCATION_AGE_MINUTES = 10L
        private const val CURRENT_LOCATION_ZOOM_LEVEL = 16
        private const val DEFAULT_LATITUDE = 37.4979
        private const val DEFAULT_LONGITUDE = 127.0276
    }
}
