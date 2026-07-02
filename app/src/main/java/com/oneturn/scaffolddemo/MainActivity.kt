package com.oneturn.scaffolddemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arkivanov.decompose.defaultComponentContext
import com.oneturn.scaffolddemo.decompose.DefaultRootComponent
import com.oneturn.scaffolddemo.decompose.RootContent
import com.oneturn.scaffolddemo.ui.theme.ScaffoldDemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val root = DefaultRootComponent(
            componentContext = defaultComponentContext(),
        )

        setContent {
            ScaffoldDemoTheme {
                RootContent(component = root)
            }
        }
    }
}
