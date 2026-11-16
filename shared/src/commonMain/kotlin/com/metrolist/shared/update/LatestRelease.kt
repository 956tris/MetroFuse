package com.metrolist.shared.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val RELEASE_REPOSITORY_URL = "https://github.com/956tris/MetroFuse"
const val RELEASE_API_BASE = "https://api.github.com/repos/956tris/MetroFuse"
const val RELEASES_API_URL = "$RELEASE_API_BASE/releases?per_page=50"

/** The repository's latest stable release; GitHub excludes drafts and prereleases from `releases/latest`. */
const val LATEST_RELEASE_URL = "$RELEASE_API_BASE/releases/latest"

@Serializable
data class LatestRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
) {
    val version: String get() = tagName.removePrefix("v")
}

private val json = Json { ignoreUnknownKeys = true }

/** Returns the latest release when it is newer than [currentVersion]. */
fun newerLatestRelease(
    responseJson: String,
    currentVersion: String,
): LatestRelease? {
    val release = json.decodeFromString<LatestRelease>(responseJson)
    val latest = ReleaseVersion.parse(release.version) ?: return null
    val current = ReleaseVersion.parse(currentVersion) ?: return null
    return release.takeIf { latest > current }
}
