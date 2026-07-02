package com.oneturn.scaffolddemo.decompose

import com.arkivanov.decompose.ComponentContext

interface ToastTestComponent {
    fun onNavigateToScreenA()
}

class DefaultToastTestComponent(
    componentContext: ComponentContext,
    private val onNavigate: () -> Unit,
) : ToastTestComponent, ComponentContext by componentContext {
    override fun onNavigateToScreenA() = onNavigate()
}

interface ScreenAComponent {
    fun onNavigateToScreenB()
}

class DefaultScreenAComponent(
    componentContext: ComponentContext,
    private val onNavigate: () -> Unit,
) : ScreenAComponent, ComponentContext by componentContext {
    override fun onNavigateToScreenB() = onNavigate()
}

interface ScreenBComponent

class DefaultScreenBComponent(
    componentContext: ComponentContext,
) : ScreenBComponent, ComponentContext by componentContext

interface ChatComponent

class DefaultChatComponent(
    componentContext: ComponentContext,
) : ChatComponent, ComponentContext by componentContext
