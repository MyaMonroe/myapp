package com.deffrow.akuji

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun AkujiOperatorSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { AkujiBridgeClient(context.applicationContext) }

    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(
            if (bridge.isConfigured()) {
                "Direct operator credentials are stored securely on this phone."
            } else {
                "Paste the HTTPS AKUJI bridge address and bridge token. The token stays encrypted in Android Keystore."
            },
        )
    }
    var busy by remember { mutableStateOf(false) }

    fun statusText(result: AkujiBridgeClient.BridgeStatus): String {
        val tools = if (result.availableTools.isEmpty()) "no private tools connected" else result.availableTools.joinToString(", ")
        return "OPERATOR CONNECTED · ${result.operatorMode.uppercase()} · $tools · execution ${if (result.executionEnabled) "ON" else "OFF"}"
    }

    fun testStoredConnection() {
        busy = true
        status = "Checking AKUJI direct operator..."
        scope.launch {
            bridge.getStatus()
                .onSuccess { result -> status = statusText(result) }
                .onFailure { error -> status = error.message ?: "AKUJI could not reach the operator bridge." }
            busy = false
        }
    }

    MaterialTheme {
        Surface(color = Color(0xFF07050A), modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "AKUJI OPERATOR",
                    color = Color(0xFFF1D99B),
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    letterSpacing = 2.sp,
                )

                Text(
                    text = status,
                    color = Color(0xFFF7EEDC),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF120B16), RoundedCornerShape(18.dp))
                        .border(1.dp, Color(0x55C9A84C), RoundedCornerShape(18.dp))
                        .padding(14.dp),
                )

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it.trim() },
                    label = { Text("HTTPS bridge address") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("Bridge token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        busy = true
                        status = "Saving and testing direct operator connection..."
                        scope.launch {
                            runCatching { bridge.saveConfiguration(endpoint, token) }
                                .onFailure { error ->
                                    status = error.message ?: "AKUJI could not save the operator connection."
                                    busy = false
                                    return@launch
                                }

                            endpoint = ""
                            token = ""
                            bridge.getStatus()
                                .onSuccess { result -> status = statusText(result) }
                                .onFailure { error ->
                                    status = error.message ?: "Saved securely, but AKUJI could not reach the operator bridge."
                                }
                            busy = false
                        }
                    },
                    enabled = !busy && endpoint.isNotBlank() && token.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF241128),
                        contentColor = Color(0xFFF1D99B),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("SAVE + TEST", fontWeight = FontWeight.Black)
                }

                if (bridge.isConfigured()) {
                    Button(
                        onClick = ::testStoredConnection,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF120B16),
                            contentColor = Color(0xFFF1D99B),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("TEST STORED CONNECTION", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF120B16),
                            contentColor = Color(0xFFF1D99B),
                        ),
                    ) {
                        Text("BACK", fontWeight = FontWeight.Bold)
                    }

                    if (bridge.isConfigured()) {
                        Button(
                            onClick = {
                                bridge.clearConfiguration()
                                endpoint = ""
                                token = ""
                                status = "Operator connection removed from this phone."
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF120B16),
                                contentColor = Color(0xFFD9B6B6),
                            ),
                        ) {
                            Text("REMOVE", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
