package com.metrolist.shared.plugins

import com.metrolist.shared.model.HomeSection

data class PluginHomePage(
    val sections: List<HomeSection> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val loaded: Boolean = false,
)
