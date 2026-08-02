package com.oneturn.transfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.oneturn.transfer.pairing.RoomCodeGenerator

/**
 * Room code entry as 4 word fields with auto-advance: typing a word (or space)
 * moves focus to the next field. Reports the full dashed code via [onCodeChange]
 * only when all 4 words are present, and shows a checksum warning otherwise.
 */
@Composable
fun RoomCodeInput(
    onCodeChange: (String) -> Unit,
    enabled: Boolean = true,
    initialCode: String = "",
    modifier: Modifier = Modifier,
) {
    val wordCount = RoomCodeGenerator.CONTENT_WORDS + 1
    val words = remember {
        val initial = RoomCodeGenerator.normalize(initialCode).split("-").filter { it.isNotBlank() }
        Array(wordCount) { i -> mutableStateOf(initial.getOrNull(i) ?: "") }
    }
    val focusRequesters = remember { Array(wordCount) { FocusRequester() } }
    var completed by remember { mutableStateOf(false) }
    val checksumOk = remember { mutableStateOf(false) }

    fun report() {
        val full = words.joinToString("-") { it.value }
        val allFilled = words.all { it.value.isNotBlank() }
        completed = allFilled
        if (allFilled) {
            val valid = RoomCodeGenerator.isValid(full)
            checksumOk.value = valid
            onCodeChange(if (valid) RoomCodeGenerator.normalize(full) else full)
        } else {
            checksumOk.value = false
            onCodeChange("")
        }
    }

    LaunchedEffect(Unit) {
        if (words.all { it.value.isNotBlank() }) report()
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        words.indices.forEach { index ->
            val state: MutableState<String> = words[index]
            OutlinedTextField(
                value = state.value,
                onValueChange = { raw ->
                    val cleaned = raw.lowercase().filter { it.isLetter() }
                    state.value = cleaned
                    if (cleaned.length >= 6 || raw.endsWith(" ") || raw.endsWith("-")) {
                        if (index < words.lastIndex) {
                            focusRequesters[index + 1].requestFocus()
                        }
                    }
                    report()
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequesters[index]),
                singleLine = true,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = if (index < words.lastIndex) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onNext = {
                        if (index < words.lastIndex) focusRequesters[index + 1].requestFocus()
                        report()
                    },
                    onDone = { report() },
                ),
                label = { Text("${index + 1}") },
            )
        }
    }

    if (completed && !checksumOk.value) {
        Text(
            "校验和校验失败，请检查单词拼写",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
