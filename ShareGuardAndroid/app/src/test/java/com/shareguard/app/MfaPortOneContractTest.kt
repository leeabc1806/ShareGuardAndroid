package com.shareguard.app

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MfaPortOneContractTest {
    @Test
    fun identityRequestUsesHttpsRedirectInsteadOfCustomScheme() {
        val request = MfaPortOneContract.createRequest("store-test", "channel-test", "identity-test")
        val redirect = request.getString("redirectUrl")
        assertEquals("https", URI(redirect).scheme)
        assertTrue(MfaPortOneContract.isCallbackUrl(redirect))
        assertTrue(request.getBoolean("forceRedirect"))
        assertEquals("identity-test", request.getString("identityVerificationId"))
        assertEquals("REDIRECTION", request.getJSONObject("windowType").getString("mobile"))
    }

    @Test
    fun includesRequiredInicisOptionsForPassWithoutCollectingIdentityInApp() {
        val request = MfaPortOneContract.createRequest("store-test", "channel-test", "identity-test")
        val inicis = request.getJSONObject("bypass").getJSONObject("inicisUnified")
        assertEquals("N", inicis.getString("flgFixedUser"))
        assertEquals("PASS", inicis.getString("directAgency"))
        assertFalse(request.has("customer"))
        assertFalse(request.has("accessToken"))
        assertFalse(request.has("apiSecret"))
    }

    @Test
    fun acceptsSuccessfulHttpsCallbackAndExplicitDefaultHttpsPort() {
        assertTrue(MfaPortOneContract.isCallbackUrl(
            "${MfaPortOneContract.REDIRECT_URL}?identityVerificationId=identity-test"))
        assertTrue(MfaPortOneContract.isCallbackUrl(
            "https://shareguard.invalid:443/mfa-complete?identityVerificationId=identity-test"))
    }

    @Test
    fun acceptsErrorCallbacksAndExistingNativeDeepLink() {
        assertTrue(MfaPortOneContract.isCallbackUrl(
            "${MfaPortOneContract.REDIRECT_URL}?code=SDK_ERROR&message=Cancelled"))
        assertTrue(MfaPortOneContract.isCallbackUrl(
            "shareguard://mfa-complete?code=SDK_ERROR&message=Cancelled"))
    }

    @Test
    fun rejectsUnrelatedOrSpoofedCompletionUrls() {
        listOf(
            "https://pg.example.test/mfa-complete?identityVerificationId=identity-test",
            "https://shareguard.invalid.evil.test/mfa-complete",
            "https://shareguard.invalid@evil.test/mfa-complete",
            "https://evil.test@shareguard.invalid/mfa-complete",
            "http://shareguard.invalid/mfa-complete",
            "https://shareguard.invalid:8443/mfa-complete",
            "https://shareguard.invalid/mfa-complete/extra",
            "https://shareguard.invalid/mfa-complete#identityVerificationId=fake",
            "shareguard://auth/callback",
            "shareguard://mfa-complete/extra"
        ).forEach { assertFalse(it, MfaPortOneContract.isCallbackUrl(it)) }
    }

    @Test
    fun rejectsMissingOrMalformedCallbackUrls() {
        assertFalse(MfaPortOneContract.isCallbackUrl(null))
        assertFalse(MfaPortOneContract.isCallbackUrl(""))
        assertFalse(MfaPortOneContract.isCallbackUrl("https://shareguard.invalid/mfa-complete?bad=%ZZ"))
    }
}
