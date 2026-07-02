package com.oneturn.scaffolddemo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen() {
    var text by remember { mutableStateOf("") }

    // 使用我们自定义的 AppScaffold，并声明这个页面需要固定 TopBar
    AppScaffold(
        keepTopBarFixed = true, // 目前测试固定模式
        topBar = {
            TopAppBar(
                title = { Text("聊天界面1") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            // 底部输入框
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 注意：这里不再需要手动加 imePadding，统一由 AppScaffold 接管，防止重复抬起。
                    .padding(8.dp)
                    .imePadding()
                ,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发送消息...") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { /* 发送逻辑 */ }) {
                    Text("发送")
                }
            }
        }
    ) { paddingValues ->
        // 中间的聊天列表内容填充
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.White),
            contentPadding = PaddingValues(16.dp),
            reverseLayout = true // 微信通常是倒序布局，最新的在最底部
        ) {
            items(50) { index ->
                Text(
                    text = "这是一条历史消息，编号: ${50 - index}",
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}
