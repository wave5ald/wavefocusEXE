package com.example.desktop.platform

/**
 * Firebase configuration shared with the Wave Focus PWA
 * (https://gen-lang-client-0965379779.web.app).
 *
 * These values mirror `pwa/js/firebase-config.js` exactly, so the desktop build signs in
 * with the same Google account and reads/writes the same Firestore document:
 *
 *     users/{uid}/apps/wavefocus
 *
 * The Web API key is a public client identifier (not a secret), which is why it can be
 * shipped inside the app just like it is inside the web bundle.
 */
object CloudConfig {
    const val PROJECT_ID = "gen-lang-client-0965379779"
    const val PROJECT_NUMBER = "108217230951"
    const val API_KEY = "AIzaSyBvKOKObBwCM8nijyV8LRhP1FROwq3oV7U"
    const val AUTH_DOMAIN = "gen-lang-client-0965379779.firebaseapp.com"
    const val STORAGE_BUCKET = "gen-lang-client-0965379779.firebasestorage.app"
    const val WEB_APP_ID = "1:108217230951:web:15843aa6255cf74fb1d6c2"
    const val WEB_CLIENT_ID = "108217230951-g30odod8il3skd5vocr2edlthseqilr2.apps.googleusercontent.com"

    /** The desktop client uses the "(default)" Firestore database of the project. */
    const val FIRESTORE_DATABASE_ID = "(default)"

    /** Path of the PWA sync document, relative to the project root. */
    const val SYNC_DOCUMENT_PATH = "users/{uid}/apps/wavefocus"

    const val DEVICE_PLATFORM = "Windows"
    const val DEVICE_LABEL = "Wave Focus Desktop (Windows)"
}
