package com.oneturn.scaffolddemo.navigation

import java.io.Serializable
import kotlinx.serialization.Serializable as KotlinxSerializable

@KotlinxSerializable
sealed interface NavKey : Serializable {
    @KotlinxSerializable
    data object ToastTest : NavKey

    @KotlinxSerializable
    data object Chat : NavKey

    @KotlinxSerializable
    data object ScreenA : NavKey

    @KotlinxSerializable
    data object ScreenB : NavKey
}
