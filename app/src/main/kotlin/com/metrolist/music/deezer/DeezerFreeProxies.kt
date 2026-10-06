/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.deezer

/**
 * Optional free regional HTTP proxies for Deezer.
 *
 * Deezer serves 180+ regions (see https://developers.deezer.com/guidelines/countries),
 * but direct media/CDN routes can be slow or blocked depending on where the user is.
 * These presets live in Deezer-supported countries across EU / Americas / Asia-Pacific
 * so users can pick the closest one for the lowest ping.
 *
 * They are plain `host:port` HTTP proxies, which is exactly what
 * [DeezerAudioProvider.proxyConfig] already accepts. Selection stays fully optional:
 * the default remains a direct connection, and picking a region only fills in the
 * existing proxy URL slot (signup/session bootstrap + search + streaming all honour it).
 *
 * Free public proxies are community-run, unstable, and can disappear at any time.
 * Treat them as disposable: if one stops working, pick another region or paste a fresh
 * `host:port` from a live list such as https://github.com/proxifly/free-proxy-list.
 */
object DeezerFreeProxies {
    data class Preset(
        val id: String,
        val region: String,
        val countryCode: String,
        val country: String,
        val hostPort: String,
        val pingHint: String,
    ) {
        val label: String = "$region · $country ($hostPort)"
    }

    val presets: List<Preset> =
        listOf(
            Preset(
                id = "eu-central-de",
                region = "EU Central",
                countryCode = "DE",
                country = "Germany",
                hostPort = "47.91.65.23:3128",
                pingHint = "Lowest ping for central Europe",
            ),
            Preset(
                id = "eu-central-at",
                region = "EU Central",
                countryCode = "AT",
                country = "Austria",
                hostPort = "213.33.126.130:80",
                pingHint = "Lowest ping for central/eastern Europe",
            ),
            Preset(
                id = "us-east",
                region = "North America",
                countryCode = "US",
                country = "United States",
                hostPort = "50.122.86.118:80",
                pingHint = "Lowest ping for North & South America",
            ),
            Preset(
                id = "us-west-alt",
                region = "North America (alt)",
                countryCode = "US",
                country = "United States",
                hostPort = "47.89.184.18:3128",
                pingHint = "Fallback if the primary US endpoint is slow",
            ),
            Preset(
                id = "latam-mx",
                region = "Latin America",
                countryCode = "MX",
                country = "Mexico",
                hostPort = "189.202.188.149:80",
                pingHint = "Closer hop for Latin America vs US",
            ),
            Preset(
                id = "asia-sg",
                region = "Asia-Pacific",
                countryCode = "SG",
                country = "Singapore",
                hostPort = "143.42.66.91:80",
                pingHint = "Lowest ping for Asia-Pacific",
            ),
            Preset(
                id = "asia-sg-alt",
                region = "Asia-Pacific (alt)",
                countryCode = "SG",
                country = "Singapore",
                hostPort = "97.74.87.226:80",
                pingHint = "Fallback if the primary SG endpoint is slow",
            ),
        )

    fun findByHostPort(hostPort: String): Preset? {
        val normalized = hostPort.trim().lowercase()
        return presets.firstOrNull { it.hostPort.lowercase() == normalized }
    }

    fun findById(id: String): Preset? = presets.firstOrNull { it.id == id }
}
