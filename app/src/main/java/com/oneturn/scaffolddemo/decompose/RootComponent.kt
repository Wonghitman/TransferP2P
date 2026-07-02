package com.oneturn.scaffolddemo.decompose

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackHandler

interface RootComponent {
    val stack: Value<ChildStack<*, Child>>
    val backHandler: BackHandler

    fun onBackClicked()

    sealed class Child {
        class ToastTest(val component: ToastTestComponent) : Child()
        class ScreenA(val component: ScreenAComponent) : Child()
        class ScreenB(val component: ScreenBComponent) : Child()
        class Chat(val component: ChatComponent) : Child()
    }
}
