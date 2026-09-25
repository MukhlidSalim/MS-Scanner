package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.EmeraldLight

@Composable
fun PinLockScreen(
    title: String = "Enter PIN",
    subtitle: String = "Protect your confidential documents",
    onPinEntered: (String) -> Boolean,
    onSuccess: () -> Unit
) {
    var enteredDigits by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    fun addDigit(d: String) {
        if (enteredDigits.length < 4) {
            val updated = enteredDigits + d
            enteredDigits = updated
            isError = false
            if (updated.length == 4) {
                val ok = onPinEntered(updated)
                if (ok) {
                    onSuccess()
                } else {
                    isError = true
                    enteredDigits = ""
                }
            }
        }
    }

    fun removeDigit() {
        if (enteredDigits.isNotEmpty()) {
            enteredDigits = enteredDigits.dropLast(1)
            isError = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isError) "Incorrect PIN, please try again" else subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(36.dp))

            // 4 Dots
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (i in 0 until 4) {
                    val filled = i < enteredDigits.length
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(
                                if (filled) {
                                    if (isError) MaterialTheme.colorScheme.error else EmeraldLight
                                } else {
                                    Color.Transparent
                                }
                            )
                            .border(
                                2.dp,
                                if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Keypad (1..9, Backspace, 0)
            val keypad = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "DEL")
            )

            for (row in keypad) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    modifier = Modifier.padding(vertical = 10.dp)
                ) {
                    for (btn in row) {
                        if (btn.isBlank()) {
                            Spacer(modifier = Modifier.size(68.dp))
                        } else if (btn == "DEL") {
                            IconButton(
                                onClick = { removeDigit() },
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .testTag("pin_del_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete")
                            }
                        } else {
                            Surface(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .clickable { addDigit(btn) }
                                    .testTag("pin_btn_$btn"),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = btn,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
