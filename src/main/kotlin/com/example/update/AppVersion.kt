package com.example.update

import java.util.Properties

/**
 * The running application version, read from `/version.properties` on the
 * classpath. Gradle uses the same file for the package version, so the
 * manifest and the binary can never disagree.
 */
object AppVersion {

    val current: String by lazy {
        val props = Properties()
        runCatching {
            AppVersion::class.java.getResourceAsStream("/version.properties")?.use { props.load(it) }
        }
        props.getProperty("version")?.trim().takeUnless { it.isNullOrEmpty() } ?: "0.0.0"
    }
}
