@file:OptIn(com.arkivanov.decompose.DelicateDecomposeApi::class)

package com.oneturn.scaffolddemo.decompose

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.router.stack.pushToFront
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

class DefaultRootComponent(
    componentContext: ComponentContext,
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()
    private val forwardNavigationLocked = AtomicBoolean(false)

    override val stack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.ToastTest,
        handleBackButton = false,
        childFactory = ::child,
    )

    override fun onBackClicked() {
        navigation.pop()
    }

    private fun pushScreen(config: Config) {
        if (!forwardNavigationLocked.compareAndSet(false, true)) return
        try {
            val snapshot = stack.value
            if (snapshot.active.configuration == config) return
            if (snapshot.backStack.any { it.configuration == config }) {
                navigation.pushToFront(config)
                return
            }
            navigation.pushNew(config)
        } finally {
            forwardNavigationLocked.set(false)
        }
    }

    private fun child(config: Config, componentContext: ComponentContext): RootComponent.Child =
        when (config) {
            Config.ToastTest -> RootComponent.Child.ToastTest(
                DefaultToastTestComponent(
                    componentContext = componentContext,
                    onNavigate = { pushScreen(Config.ScreenA) },
                ),
            )

            Config.ScreenA -> RootComponent.Child.ScreenA(
                DefaultScreenAComponent(
                    componentContext = componentContext,
                    onNavigate = { pushScreen(Config.ScreenB) },
                ),
            )

            Config.ScreenB -> RootComponent.Child.ScreenB(
                DefaultScreenBComponent(componentContext = componentContext),
            )

            Config.Chat -> RootComponent.Child.Chat(
                DefaultChatComponent(componentContext = componentContext),
            )
        }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object ToastTest : Config

        @Serializable
        data object ScreenA : Config

        @Serializable
        data object ScreenB : Config

        @Serializable
        data object Chat : Config
    }
}
