package dev.metrofuse.runtime

import dev.metrofuse.api.LoginRequest
import dev.metrofuse.api.WebLoginRequest
import dev.metrofuse.sdk.acceptsBrowserNavigation
import dev.metrofuse.sdk.captureBrowserCookies
import dev.metrofuse.sdk.validateBrowserRequest

internal fun WebLoginRequest.validate(form: LoginRequest) {
    validateBrowserRequest()
    require(form.fields.map { it.id } == listOf(cookieField))
}

fun WebLoginRequest.acceptsNavigation(value: String): Boolean = acceptsBrowserNavigation(value)

fun WebLoginRequest.captureCookies(header: String): Map<String, String>? = captureBrowserCookies(header)
