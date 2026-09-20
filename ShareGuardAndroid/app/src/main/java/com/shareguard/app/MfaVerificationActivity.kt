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
import org.json.JSONObject
import java.util.UUID

class MfaVerificationActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val RESULT_TRUST_SCORE = "trust_score"
        const val RESULT_TRUST_BONUS = "trust_bonus"
        private const val CALLBACK_SCHEME = "shareguard"
        private const val CALLBACK_HOST = "mfa-complete"

        fun intent(context: Activity, accessToken: String): Intent =
            Intent(context, MfaVerificationActivity::class.java)
                .putExtra(EXTRA_ACCESS_TOKEN, accessToken)
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var viewModel: MfaViewModel
    private lateinit var identityVerificationId: String
    private var verificationSubmitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identityVerificationId = savedInstanceState?.getString("verification_id")
            ?: "identity-verification-${UUID.randomUUID()}"
        buildContentView()
        observeVerification()
        handleCallback(intent.data)
        if (!verificationSubmitted && savedInstanceState == null) loadPortOne()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent.data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("verification_id", identityVerificationId)
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
                    route(request.url.toString())

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
        val accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN).orEmpty()
        if (storeId.isBlank() || channelKey.isBlank() || apiBaseUrl.isBlank() || accessToken.isBlank()) {
            showTerminalError(
                "PORTONE_STORE_ID, PORTONE_IDENTITY_CHANNEL_KEY, " +
                    "SHAREGUARD_API_BASE_URL 및 로그인 세션을 확인하세요."
            )
            return
        }

        val requestJson = JSONObject()
            .put("storeId", storeId)
            .put("channelKey", channelKey)
            .put("identityVerificationId", identityVerificationId)
            .put("redirectUrl", "$CALLBACK_SCHEME://$CALLBACK_HOST")
            .put("forceRedirect", true)
            .put("windowType", JSONObject().put("mobile", "REDIRECTION"))
            .put("bypass", JSONObject().put("danal", JSONObject().put("CPTITLE", "ShareGuard")))
            .toString()

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
                  } else {
                    location.href = '$CALLBACK_SCHEME://$CALLBACK_HOST?identityVerificationId=' +
                      encodeURIComponent(request.identityVerificationId);
                  }
                }).catch((error) => {
                  location.href = '$CALLBACK_SCHEME://$CALLBACK_HOST?code=SDK_ERROR&message=' +
                    encodeURIComponent(error && error.message ? error.message : '인증창 호출 실패');
                });
              </script>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://shareguard.app/", html, "text/html", "UTF-8", null)
    }

    private fun route(rawUrl: String): Boolean {
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
                !fallbackUrl.isNullOrBlank() -> webView.loadUrl(fallbackUrl)
                !external.`package`.isNullOrBlank() -> startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${external.`package`}"))
                )
                else -> toast("PASS 앱이 설치되어 있는지 확인하세요.")
            }
        }
    }

    private fun handleCallback(uri: Uri?) {
        if (uri?.scheme != CALLBACK_SCHEME || uri.host != CALLBACK_HOST || verificationSubmitted) return
        val errorCode = uri.getQueryParameter("code")
        if (!errorCode.isNullOrBlank()) {
            showTerminalError(uri.getQueryParameter("message") ?: "본인인증이 취소되거나 실패했습니다.")
            return
        }
        val callbackId = uri.getQueryParameter("identityVerificationId") ?: identityVerificationId
        if (callbackId != identityVerificationId) {
            showTerminalError("인증 요청 식별자가 일치하지 않습니다.")
            return
        }
        verificationSubmitted = true
        viewModel.verify(
            BuildConfig.SHAREGUARD_API_BASE_URL,
            intent.getStringExtra(EXTRA_ACCESS_TOKEN).orEmpty(),
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
