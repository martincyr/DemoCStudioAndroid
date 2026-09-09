package com.martincyr.demoagentsdk

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation-compose routes. Each destination is a serializable object so the back
 * stack can be saved and restored across configuration changes and process death.
 */
sealed interface AppScreen {
    @Serializable
    data object Selection : AppScreen

    @Serializable
    data object AndroidSdk : AppScreen

    @Serializable
    data object WebChat : AppScreen

    @Serializable
    data object NativeClient : AppScreen
}
