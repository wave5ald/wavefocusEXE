package com.example.data.auth

import android.content.Context
import android.util.Log
import com.example.desktop.platform.CloudConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class GoogleAccount(
    val uid: String,
    val email: String,
    val displayName: String,
    val photoUrl: String?
)

enum class GoogleAuthStatus {
    SIGNED_OUT,
    SIGNING_IN,
    SIGNED_IN,
    ERROR
}

/**
 * Google sign-in for the desktop build.
 *
 * The Firebase project only has an OAuth *web* client (the one the PWA uses), and a web client
 * cannot use a `http://127.0.0.1` redirect URI (Google rejects it with `redirect_uri_mismatch`).
 * The desktop app therefore runs the exact same browser flow the PWA uses — the official Firebase
 * Auth JavaScript SDK, served from a loopback page — and receives the resulting Firebase ID token
 * / refresh token over the loopback channel.
 *
 * `localhost` is one of the project's authorised Auth domains, so no extra console setup is needed.
 */
class GoogleAuthManager private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("wave_focus_auth_prefs", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(readAccount())
    val account: StateFlow<GoogleAccount?> = _account.asStateFlow()

    private val _status = MutableStateFlow(
        if (_account.value != null) GoogleAuthStatus.SIGNED_IN else GoogleAuthStatus.SIGNED_OUT
    )
    val status: StateFlow<GoogleAuthStatus> = _status.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    @Volatile
    private var idToken: String? = prefs.getString(KEY_ID_TOKEN, null)

    @Volatile
    private var refreshToken: String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    @Volatile
    private var tokenExpiresAt: Long = prefs.getLong(KEY_EXPIRES_AT, 0L)

    private val http = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val random = SecureRandom()

    // ------------------------------------------------------------------ state

    val isSignedIn: Boolean get() = _account.value != null

    private fun readAccount(): GoogleAccount? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        return GoogleAccount(
            uid = uid,
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
            displayName = prefs.getString(KEY_NAME, "").orEmpty(),
            photoUrl = prefs.getString(KEY_PHOTO, null)
        )
    }

    private fun persist(account: GoogleAccount, idToken: String, refreshToken: String?, expiresAt: Long) {
        this.idToken = idToken
        this.refreshToken = refreshToken ?: this.refreshToken
        this.tokenExpiresAt = expiresAt
        prefs.edit()
            .putString(KEY_UID, account.uid)
            .putString(KEY_EMAIL, account.email)
            .putString(KEY_NAME, account.displayName)
            .putString(KEY_PHOTO, account.photoUrl)
            .putString(KEY_ID_TOKEN, idToken)
            .putString(KEY_REFRESH_TOKEN, this.refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun signOut() {
        _account.value = null
        _status.value = GoogleAuthStatus.SIGNED_OUT
        _message.value = ""
        idToken = null
        refreshToken = null
        tokenExpiresAt = 0L
        prefs.edit().clear().apply()
    }

    // ------------------------------------------------------------------ token

    /**
     * Returns a valid Firebase ID token, refreshing it with the stored refresh token when it is
     * about to expire. Returns null when the user has to sign in again.
     */
    fun validIdToken(forceRefresh: Boolean = false): String? {
        if (_account.value == null) return null
        val current = idToken
        if (!forceRefresh && !current.isNullOrBlank() && tokenExpiresAt - 60_000L > System.currentTimeMillis()) {
            return current
        }
        return refreshIdToken()
    }

    private fun refreshIdToken(): String? {
        val refresh = refreshToken ?: return null
        return try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .build()
            val request = Request.Builder()
                .url("https://securetoken.googleapis.com/v1/token?key=${CloudConfig.API_KEY}")
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Token refresh failed: HTTP ${response.code} $text")
                    if (response.code == 400 || response.code == 401 || response.code == 403) {
                        // Refresh token revoked / invalid: force a new interactive sign-in.
                        signOut()
                    }
                    return null
                }
                val json = JSONObject(text)
                val newToken = json.optString("id_token", "")
                val newRefresh = json.optString("refresh_token", "")
                if (newToken.isBlank()) return null
                idToken = newToken
                if (newRefresh.isNotBlank()) refreshToken = newRefresh
                tokenExpiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L
                prefs.edit()
                    .putString(KEY_ID_TOKEN, newToken)
                    .putString(KEY_REFRESH_TOKEN, refreshToken)
                    .putLong(KEY_EXPIRES_AT, tokenExpiresAt)
                    .apply()
                newToken
            }
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh error: ${e.message}")
            null
        }
    }

    // --------------------------------------------------------------- sign-in

    /**
     * Runs the interactive Google sign-in in the system browser and waits for the loopback
     * callback. Call from a background coroutine (it blocks on network + user interaction).
     */
    suspend fun signIn(): Result<GoogleAccount> = withContext(Dispatchers.IO) {
        _status.value = GoogleAuthStatus.SIGNING_IN
        _message.value = "Waiting for Google sign-in in your browser…"

        val nonce = ByteArray(24).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        val result = CompletableDeferred<GoogleAccount>()
        var server: HttpServer? = null
        try {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "wavefocus-google-signin").apply { isDaemon = true }
                }
                createContext("/") { exchange -> handleRoot(exchange, nonce) }
                createContext("/complete") { exchange -> handleComplete(exchange, nonce, result) }
                createContext("/done") { exchange ->
                    exchange.respond(
                        if (result.isCompleted) 200 else 409,
                        donePage(result.getCompletedOrNull())
                    )
                }
                start()
            }

            val port = server.address.port
            val url = "http://localhost:$port/?s=${URLEncoder.encode(nonce, "UTF-8")}"
            Log.d(TAG, "Opening browser for Google sign-in: $url")
            openBrowser(url)

            val account = withTimeout(SIGN_IN_TIMEOUT_MS) { result.await() }
            _account.value = account
            _status.value = GoogleAuthStatus.SIGNED_IN
            _message.value = "Signed in as ${account.email}"
            Result.success(account)
        } catch (e: Exception) {
            Log.w(TAG, "Google sign-in failed: ${e.message}")
            _status.value = GoogleAuthStatus.ERROR
            _message.value = e.message ?: "Google sign-in failed"
            Result.failure(e)
        } finally {
            try {
                server?.stop(0)
            } catch (_: Exception) {
                // already stopped
            }
        }
    }

    private fun handleRoot(exchange: HttpExchange, nonce: String) {
        try {
            val query = exchange.requestURI.query.orEmpty()
            val state = query.split('&')
                .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
                .firstOrNull { it[0] == "s" }
                ?.let { java.net.URLDecoder.decode(it[1], "UTF-8") }
            if (state != nonce) {
                exchange.respond(403, errorPage("This sign-in link is no longer valid. Start again from the app."))
                return
            }
            exchange.respond(200, signInPage(nonce))
        } catch (e: Exception) {
            Log.w(TAG, "loopback root handler error: ${e.message}")
        }
    }

    private fun handleComplete(
        exchange: HttpExchange,
        nonce: String,
        result: CompletableDeferred<GoogleAccount>
    ) {
        try {
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                exchange.respond(405, errorPage("Method not allowed."))
                return
            }
            val payload = JSONObject(exchange.readBody())
            if (payload.optString("state") != nonce) {
                exchange.respond(403, errorPage("This sign-in link is no longer valid. Start again from the app."))
                return
            }
            val uid = payload.optString("uid")
            val idTokenValue = payload.optString("idToken")
            if (uid.isBlank() || idTokenValue.isBlank()) {
                exchange.respond(400, errorPage("Google did not return a usable account. Please try again."))
                return
            }
            val account = GoogleAccount(
                uid = uid,
                email = payload.optString("email"),
                displayName = payload.optString("name").ifBlank { payload.optString("email") },
                photoUrl = payload.optString("photo").ifBlank { null }
            )
            val refresh = payload.optString("refreshToken").ifBlank { null }
            val expiresAt = payload.optLong("expiresAt", System.currentTimeMillis() + 3_600_000L)
            persist(account, idTokenValue, refresh, expiresAt)
            exchange.respond(200, donePage(account))
            result.complete(account)
        } catch (e: Exception) {
            Log.w(TAG, "loopback complete handler error: ${e.message}")
            try {
                exchange.respond(500, errorPage("Could not complete sign-in: ${e.message}"))
            } catch (_: Exception) {
                // response already sent
            }
        }
    }

    private fun openBrowser(url: String) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                val desktop = java.awt.Desktop.getDesktop()
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(URI(url))
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Desktop.browse failed: ${e.message}")
        }
        try {
            Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the browser: ${e.message}")
            throw IllegalStateException("Could not open your web browser. Open this link manually: $url")
        }
    }

    // ------------------------------------------------------------------- html

    private fun signInPage(nonce: String): String = PAGE_SHELL
        .replace("%NONCE_JSON%", JSONObject.quote(nonce))
        .replace("%API_KEY%", CloudConfig.API_KEY)
        .replace("%AUTH_DOMAIN%", CloudConfig.AUTH_DOMAIN)
        .replace("%PROJECT_ID%", CloudConfig.PROJECT_ID)
        .replace("%APP_ID%", CloudConfig.WEB_APP_ID)
        .replace("%SENDER_ID%", CloudConfig.PROJECT_NUMBER)
        .replace("%STORAGE_BUCKET%", CloudConfig.STORAGE_BUCKET)

    private fun donePage(account: GoogleAccount?): String {
        val who = account?.email ?: "your Google account"
        return """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8"><title>Wave Focus — signed in</title>
            <style>
              html,body{height:100%;margin:0;background:#0B0F19;color:#E2E8F0;
                font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
              .wrap{height:100%;display:grid;place-content:center;justify-items:center;gap:14px;text-align:center;padding:24px}
              .badge{width:72px;height:72px;border-radius:50%;display:grid;place-items:center;
                background:rgba(16,185,129,.16);color:#10B981;font-size:34px}
              h1{font-size:1.25rem;margin:0;font-weight:750}
              p{margin:0;color:#94A3B8;font-size:.9rem;max-width:360px;line-height:1.5}
            </style></head>
            <body><div class="wrap">
              <div class="badge">✓</div>
              <h1>Signed in</h1>
              <p>Wave Focus is connected as <b style="color:#E2E8F0">${escapeHtml(who)}</b>.<br>You can close this tab and return to the app.</p>
            </div></body></html>
        """.trimIndent()
    }

    private fun errorPage(message: String): String = """
        <!DOCTYPE html>
        <html lang="en"><head><meta charset="utf-8"><title>Wave Focus — sign-in problem</title>
        <style>
          html,body{height:100%;margin:0;background:#0B0F19;color:#E2E8F0;
            font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
          .wrap{height:100%;display:grid;place-content:center;justify-items:center;gap:14px;text-align:center;padding:24px}
          h1{font-size:1.15rem;margin:0;font-weight:750}
          p{margin:0;color:#94A3B8;font-size:.9rem;max-width:400px;line-height:1.5}
        </style></head>
        <body><div class="wrap"><div style="font-size:34px">⚠️</div>
          <h1>Sign-in problem</h1><p>${escapeHtml(message)}</p></div></body></html>
    """.trimIndent()

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        responseHeaders.set("Cache-Control", "no-store")
        responseHeaders.set("Referrer-Policy", "no-referrer")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.readBody(): String {
        val length = requestHeaders.getFirst("Content-Length")?.toIntOrNull() ?: -1
        return if (length >= 0) {
            requestBody.readNBytes(length).toString(Charsets.UTF_8)
        } else {
            requestBody.readBytes().toString(Charsets.UTF_8)
        }
    }

    companion object {
        private const val TAG = "GoogleAuthManager"
        private const val PREFS_KEY = "wave_focus_auth"
        private const val KEY_UID = "uid"
        private const val KEY_EMAIL = "email"
        private const val KEY_NAME = "name"
        private const val KEY_PHOTO = "photo"
        private const val KEY_ID_TOKEN = "id_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val SIGN_IN_TIMEOUT_MS = 5 * 60 * 1000L

        @Volatile
        private var instance: GoogleAuthManager? = null

        fun getInstance(context: Context): GoogleAuthManager =
            instance ?: synchronized(this) {
                instance ?: GoogleAuthManager(context.applicationContext).also { instance = it }
            }
    }
}

private fun <T> CompletableDeferred<T>.getCompletedOrNull(): T? =
    if (isCompleted) runCatching { getCompleted() }.getOrNull() else null

/**
 * Loopback sign-in page. Runs the official Firebase Auth web SDK (the same version the PWA
 * bundles) against the shared project, then hands the credentials back to the desktop app.
 */
private val PAGE_SHELL = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Wave Focus — Sign in</title>
<style>
  :root { color-scheme: dark; }
  html, body { height: 100%; margin: 0; }
  body {
    background:
      radial-gradient(120% 60% at 100% -10%, rgba(56,189,248,.12), transparent 60%),
      radial-gradient(90% 50% at -10% 0%, rgba(129,140,248,.10), transparent 55%),
      #0B0F19;
    color: #E2E8F0;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    display: grid;
    place-items: center;
  }
  .card {
    width: min(92vw, 380px);
    background: #131B2E;
    border: 1px solid #334155;
    border-radius: 20px;
    padding: 26px 24px;
    box-shadow: 0 18px 45px rgba(2,6,23,.55);
    text-align: center;
  }
  .logo {
    width: 62px; height: 62px; border-radius: 18px; margin: 0 auto 14px;
    background: linear-gradient(160deg, #38BDF8, #2563EB);
    display: grid; place-items: center;
    box-shadow: 0 10px 26px rgba(56,189,248,.28);
  }
  .logo svg { width: 36px; height: 36px; }
  h1 { font-size: 1.22rem; font-weight: 750; letter-spacing: -.02em; margin: 0 0 6px; }
  p { margin: 0; font-size: .86rem; line-height: 1.5; color: #94A3B8; }
  button {
    margin-top: 20px; width: 100%; min-height: 48px;
    display: inline-flex; align-items: center; justify-content: center; gap: 10px;
    border: 0; border-radius: 14px; cursor: pointer;
    background: #38BDF8; color: #082F49;
    font: inherit; font-size: .95rem; font-weight: 750;
    box-shadow: 0 8px 22px rgba(56,189,248,.28);
    transition: transform .15s ease, filter .2s ease;
  }
  button:hover { filter: brightness(1.05); }
  button:active { transform: scale(.97); }
  button[disabled] { opacity: .55; cursor: default; }
  .gicon { width: 20px; height: 20px; background: #fff; border-radius: 50%; display: grid; place-items: center; }
  .gicon svg { width: 13px; height: 13px; }
  .fine { margin-top: 14px; font-size: .72rem; color: #64748B; }
  .err { margin-top: 14px; font-size: .8rem; color: #F43F5E; white-space: pre-wrap; }
</style>
</head>
<body>
  <div class="card">
    <div class="logo" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none"><path d="M2 14c2.5 0 2.5-3 5-3s2.5 3 5 3 2.5-3 5-3 2.5 3 5 3" stroke="#FBBF24" stroke-width="2.4" stroke-linecap="round"/><circle cx="18" cy="6.5" r="2.5" fill="#FBBF24"/></svg>
    </div>
    <h1>Wave Focus</h1>
    <p id="lead">Sign in with the same Google account you use in the Wave Focus app to sync your routine, focus sessions and notes.</p>
    <button id="go" type="button">
      <span class="gicon" aria-hidden="true">
        <svg viewBox="0 0 48 48"><path fill="#4285F4" d="M45.1 24.5c0-1.6-.1-2.8-.4-4H24v7.6h12c-.2 2-1.5 5-4.4 7l6.7 5.2c4-3.7 6.8-9.1 6.8-15.8z"/><path fill="#34A853" d="M24 46c5.9 0 10.9-2 14.5-5.3l-6.9-5.4c-1.9 1.3-4.4 2.2-7.6 2.2-5.8 0-10.8-3.9-12.6-9.2l-7.1 5.5C8 40.9 15.4 46 24 46z"/><path fill="#FBBC05" d="M11.4 28.3c-.5-1.4-.8-2.8-.8-4.3s.3-3 .8-4.3l-7.1-5.5C2.8 17.1 2 20.4 2 24s.8 6.9 2.3 9.8l7.1-5.5z"/><path fill="#EA4335" d="M24 10.4c4.1 0 6.9 1.8 8.5 3.3l6.2-6C34.9 4.2 29.9 2 24 2 15.4 2 8 7.1 4.3 14.2l7.1 5.5C13.2 14.3 18.2 10.4 24 10.4z"/></svg>
      </span>
      <span id="goLabel">Continue with Google</span>
    </button>
    <div class="fine">A Google window opens on top of this page. Nothing is stored on any server except your own Firebase account.</div>
    <div class="err" id="err" hidden></div>
  </div>

<script type="module">
import { initializeApp } from "https://www.gstatic.com/firebasejs/11.6.1/firebase-app.js";
import {
  getAuth, GoogleAuthProvider, signInWithPopup, signInWithRedirect,
  getRedirectResult,
} from "https://www.gstatic.com/firebasejs/11.6.1/firebase-auth.js";

const STATE = %NONCE_JSON%;

const app = initializeApp({
  apiKey: "%API_KEY%",
  authDomain: "%AUTH_DOMAIN%",
  projectId: "%PROJECT_ID%",
  storageBucket: "%STORAGE_BUCKET%",
  messagingSenderId: "%SENDER_ID%",
  appId: "%APP_ID%",
});

const auth = getAuth(app);
auth.useDeviceLanguage();
const provider = new GoogleAuthProvider();
provider.setCustomParameters({ prompt: "select_account" });

const go = document.getElementById("go");
const goLabel = document.getElementById("goLabel");
const lead = document.getElementById("lead");
const errBox = document.getElementById("err");

function showError(err) {
  const code = (err && err.code) || "";
  const message = (err && err.message) || String(err);
  errBox.hidden = false;
  errBox.textContent = code ? code + "\n" + message : message;
  go.disabled = false;
  goLabel.textContent = "Continue with Google";
}

function expiresAtFromToken(token) {
  try {
    const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    return payload.exp ? payload.exp * 1000 : Date.now() + 3600000;
  } catch (e) {
    return Date.now() + 3600000;
  }
}

async function finish(user) {
  const idToken = await user.getIdToken(true);
  const refreshToken =
    user.refreshToken ||
    (user.stsTokenManager && user.stsTokenManager.refreshToken) ||
    "";
  const body = {
    state: STATE,
    uid: user.uid,
    email: user.email || "",
    name: user.displayName || "",
    photo: user.photoURL || "",
    idToken: idToken,
    refreshToken: refreshToken,
    expiresAt: expiresAtFromToken(idToken),
  };
  const response = await fetch("/complete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error("The desktop app did not accept the sign-in (" + response.status + ").");
  lead.textContent = "Signed in as " + (user.email || user.displayName || "your Google account") + ". You can close this tab.";
  go.hidden = true;
  errBox.hidden = true;
  setTimeout(() => { window.location.href = "/done"; }, 900);
}

go.addEventListener("click", async () => {
  go.disabled = true;
  goLabel.textContent = "Opening Google…";
  errBox.hidden = true;
  try {
    const result = await signInWithPopup(auth, provider);
    await finish(result.user);
  } catch (err) {
    const code = (err && err.code) || "";
    if (code === "auth/popup-blocked" ||
        code === "auth/operation-not-supported-in-this-environment" ||
        code === "auth/cancelled-popup-request" ||
        code === "auth/popup-closed-by-user") {
      try {
        await signInWithRedirect(auth, provider);
        return;
      } catch (err2) {
        showError(err2);
        return;
      }
    }
    showError(err);
  }
});

getRedirectResult(auth)
  .then((result) => { if (result && result.user) return finish(result.user); })
  .catch(showError);
</script>
</body>
</html>
"""
