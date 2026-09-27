package com.shareguard.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

class MfaVerificationActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val RESULT_TRUST_SCORE = "trust_score"
        const val RESULT_TRUST_BONUS = "trust_bonus"
        private const val CALLBACK_SCHEME = MfaPortOneContract.CALLBACK_SCHEME
        private const val CALLBACK_HOST = MfaPortOneContract.CALLBACK_HOST
        private const val SESSION_PREFS = "shareguard_session"
        private const val PENDING_ID = "pending_mfa_id"
        private const val PENDING_USER = "pending_mfa_user"
        private const val PENDING_AT = "pending_mfa_started_at"
        private const val REQUEST_TTL_MS = 20L * 60L * 1000L

        fun intent(context: Activity, accessToken: String): Intent =
            Intent(context, MfaVerificationActivity::class.java)
                .putExtra(EXTRA_ACCESS_TOKEN, accessToken)
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var viewModel: MfaViewModel
    private lateinit var identityVerificationId: String
    private var accessToken = ""
    private var verificationSubmitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
        val returningFromPass = MfaPortOneContract.isCallbackUrl(intent.data?.toString())
        val pendingAge = System.currentTimeMillis() - preferences.getLong(PENDING_AT, 0L)
        val canRestorePending = pendingAge in 0 until REQUEST_TTL_MS &&
            preferences.getString(PENDING_USER, null) == preferences.getString("user_id", null)
        identityVerificationId = savedInstanceState?.getString("verification_id")
            ?: (if (returningFromPass && canRestorePending) preferences.getString(PENDING_ID, null) else null)
            ?: "identity-verification-${UUID.randomUUID()}"
        // External callback intents have no access_token extra. Keep the original session.
        accessToken = savedInstanceState?.getString(EXTRA_ACCESS_TOKEN)
            ?: intent.getStringExtra(EXTRA_ACCESS_TOKEN)
            ?: (if (returningFromPass && canRestorePending) preferences.getString("access_token", null) else null)
            ?: ""
        buildContentView()
        observeVerification()
        handleCallback(intent.data)
        if (returningFromPass) return
        if (!verificationSubmitted && viewModel.state.value == MfaViewModel.State.Idle) {
            val webState = savedInstanceState?.getBundle("web_state")
            if (webState == null || webView.restoreState(webState) == null) loadPortOne()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Preserve launch extras for both the verification request and Activity recreation.
        setIntent(Intent(this.intent).setData(intent.data))
        handleCallback(intent.data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("verification_id", identityVerificationId)
        outState.putString(EXTRA_ACCESS_TOKEN, accessToken)
        outState.putBundle("web_state", Bundle().also { webView.saveState(it) })
        super.onSaveInstanceState(outState)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun buildContentView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && route(request.url.toString())

                override fun onPageFinished(view: WebView, url: String) {
                    if (!verificationSubmitted) this@MfaVerificationActivity.progress.visibility = View.GONE
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = route(url)
            }
        }
        progress = ProgressBar(this)
        status = TextView(this).apply {
            text = "PASS 본인인증을 준비하고 있습니다."
            textSize = 15f
            setTextColor(Color.rgb(17, 24, 39))
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(32, 20, 32, 20)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(webView, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, 140, Gravity.BOTTOM))
            addView(progress, FrameLayout.LayoutParams(90, 90, Gravity.CENTER))
        })
        viewModel = ViewModelProvider(this)[MfaViewModel::class.java]
    }

    private fun loadPortOne() {
        val storeId = BuildConfig.PORTONE_STORE_ID
        val channelKey = BuildConfig.PORTONE_IDENTITY_CHANNEL_KEY
        val apiBaseUrl = BuildConfig.SHAREGUARD_API_BASE_URL
        if (storeId.isBlank() || channelKey.isBlank() || apiBaseUrl.isBlank() || accessToken.isBlank()) {
            showTerminalError(
                "PORTONE_STORE_ID, PORTONE_IDENTITY_CHANNEL_KEY, " +
                    "SHAREGUARD_API_BASE_URL 및 로그인 세션을 확인하세요."
            )
            return
        }

        // Persist only request metadata. The existing login session provides the token
        // if Android kills the app while PASS is in the foreground.
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).let { preferences ->
            preferences.edit()
                .putString(PENDING_ID, identityVerificationId)
                .putString(PENDING_USER, preferences.getString("user_id", null))
                .putLong(PENDING_AT, System.currentTimeMillis())
                .apply()
        }

        val requestJson = MfaPortOneContract.createRequest(storeId, channelKey, identityVerificationId).toString()

        val html = """
            <!doctype html>
            <html lang="ko"><head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <script src="https://cdn.portone.io/v2/browser-sdk.js"></script>
            </head><body>
              <script>
                const request = $requestJson;
                PortOne.requestIdentityVerification(request).then((response) => {
                  if (response && response.code) {
                    location.href = '$CALLBACK_SCHEME://$CALLBACK_HOST?code=' +
                      encodeURIComponent(response.code) + '&message=' + encodeURIComponent(response.message || '인증 실패');
                  } else if (response && response.identityVerificationId) {
                    location.href = request.redirectUrl + '?identityVerificationId=' +
                      encodeURIComponent(response.identityVerificationId);
                  }
                  // A redirection flow has no Promise result. Wait for the actual redirect.
                }).catch((error) => {
                  location.href = '$CALLBACK_SCHEME://$CALLBACK_HOST?code=SDK_ERROR&message=' +
                    encodeURIComponent(error && error.message ? error.message : '인증창 호출 실패');
                });
              </script>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(MfaPortOneContract.WEB_ORIGIN, html, "text/html", "UTF-8", null)
    }

    private fun route(rawUrl: String): Boolean {
        // Check the HTTP(S) completion address before normal web navigation. Never
        // contact the sentinel host, and never treat arbitrary PG pages as completion.
        if (MfaPortOneContract.isCallbackUrl(rawUrl)) {
            handleCallback(Uri.parse(rawUrl))
            return true
        }
        val uri = Uri.parse(rawUrl)
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> false
            CALLBACK_SCHEME -> {
                handleCallback(uri)
                true
            }
            "intent" -> {
                launchIntentUri(rawUrl)
                true
            }
            else -> {
                launchExternal(Intent(Intent.ACTION_VIEW, uri), null)
                true
            }
        }
    }

    private fun launchIntentUri(rawUrl: String) {
        val external = runCatching { Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME) }.getOrNull()
        if (external == null) {
            toast("외부 인증 앱 주소를 처리할 수 없습니다.")
            return
        }
        external.addCategory(Intent.CATEGORY_BROWSABLE)
        external.component = null
        external.selector = null
        launchExternal(external, external.getStringExtra("browser_fallback_url"))
    }

    private fun launchExternal(external: Intent, fallbackUrl: String?) {
        try {
            startActivity(external)
        } catch (_: ActivityNotFoundException) {
            when {
                fallbackUrl?.let { Uri.parse(it).scheme in setOf("https", "http") } == true -> webView.loadUrl(fallbackUrl)
                !external.`package`.isNullOrBlank() -> {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${external.`package`}")))
                    }.onFailure { toast("PASS 앱을 설치한 후 다시 시도해주세요.") }
                }
                else -> toast("PASS 앱이 설치되어 있는지 확인하세요.")
            }
        }
    }

    private fun handleCallback(uri: Uri?) {
        if (uri == null || !MfaPortOneContract.isCallbackUrl(uri.toString()) || verificationSubmitted) return
        val errorCode = uri.getQueryParameter("code")
        if (!errorCode.isNullOrBlank()) {
            showTerminalError(uri.getQueryParameter("message") ?: "본인인증이 취소되거나 실패했습니다.")
            return
        }
        val callbackId = uri.getQueryParameter("identityVerificationId")
        if (callbackId != identityVerificationId) {
            showTerminalError("인증 요청 식별자가 일치하지 않습니다.")
            return
        }
        if (accessToken.isBlank()) {
            showTerminalError("로그인 세션이 없습니다. 다시 로그인한 뒤 본인인증을 진행해주세요.")
            return
        }
        verificationSubmitted = true
        viewModel.verify(
            BuildConfig.SHAREGUARD_API_BASE_URL,
            accessToken,
            callbackId
        )
    }

    private fun observeVerification() {
        viewModel.state.observe(this) { state ->
            when (state) {
                MfaViewModel.State.Idle -> Unit
                MfaViewModel.State.Verifying -> {
                    progress.visibility = View.VISIBLE
                    status.text = "서버에서 포트원 인증 결과를 검증하고 있습니다."
                }
                is MfaViewModel.State.Verified -> {
                    getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit()
                        .remove(PENDING_ID).remove(PENDING_USER).remove(PENDING_AT).apply()
                    setResult(RESULT_OK, Intent()
                        .putExtra(RESULT_TRUST_SCORE, state.trustScore)
                        .putExtra(RESULT_TRUST_BONUS, state.trustBonus))
                    finish()
                }
                is MfaViewModel.State.Failed -> showTerminalError(state.message)
            }
        }
    }

    private fun showTerminalError(message: String) {
        progress.visibility = View.GONE
        status.text = "$message\n뒤로 가서 다시 시도해주세요."
        status.setTextColor(Color.rgb(185, 28, 28))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
