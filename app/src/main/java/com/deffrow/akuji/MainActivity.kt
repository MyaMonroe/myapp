package com.deffrow.akuji

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance(),
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val scope = rememberCoroutineScope()
            val liveVoice = remember { AkujiLiveVoice() }
            var showSkills by rememberSaveable { mutableStateOf(false) }
            var liveActive by remember { mutableStateOf(false) }
            var liveStatus by remember { mutableStateOf("LIVE OFF") }
            var liveCaption by remember { mutableStateOf("") }

            fun beginLiveVoice() {
                if (liveActive) return
                liveStatus = "CONNECTING LIVE VOICE"
                liveCaption = ""
                scope.launch {
                    runCatching {
                        liveVoice.start(
                            onInputTranscript = { text ->
                                scope.launch { liveCaption = "YOU: $text" }
                            },
                            onOutputTranscript = { text ->
                                scope.launch { liveCaption = "AKUJI: $text" }
                            },
                        )
                    }.onSuccess {
                        liveActive = liveVoice.isActive
                        liveStatus = if (liveActive) "LIVE VOICE" else "LIVE READY"
                    }.onFailure { error ->
                        liveActive = false
                        liveStatus = "LIVE ERROR"
                        liveCaption = error.message ?: "AKUJI could not start Live voice."
                    }
                }
            }

            val livePermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    beginLiveVoice()
                } else {
                    liveActive = false
                    liveStatus = "MICROPHONE BLOCKED"
                    liveCaption = "AKUJI needs microphone permission for Live voice."
                }
            }

            fun toggleLiveVoice() {
                if (liveActive || liveVoice.isActive) {
                    liveVoice.stop()
                    liveActive = false
                    liveStatus = "LIVE OFF"
                    liveCaption = ""
                    return
                }

                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED

                if (granted) beginLiveVoice()
                else livePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            DisposableEffect(liveVoice) {
                onDispose {
                    liveVoice.stop()
                }
            }

            if (showSkills) {
                AkujiSkillsScreen(onBack = { showSkills = false })
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    AkujiApp()

                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        TextButton(
                            onClick = ::toggleLiveVoice,
                            modifier = Modifier
                                .background(Color(0xCC120B16), RoundedCornerShape(100.dp))
                                .border(1.dp, Color(0x88C9A84C), RoundedCornerShape(100.dp)),
                        ) {
                            Text(
                                text = if (liveActive) "STOP LIVE" else "LIVE",
                                color = Color(0xFFF1D99B),
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp,
                            )
                        }

                        TextButton(
                            onClick = { showSkills = true },
                            modifier = Modifier
                                .background(Color(0xCC120B16), RoundedCornerShape(100.dp))
                                .border(1.dp, Color(0x88C9A84C), RoundedCornerShape(100.dp)),
                        ) {
                            Text(
                                text = "SKILLS",
                                color = Color(0xFFF1D99B),
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp,
                            )
                        }
                    }

                    if (liveStatus != "LIVE OFF" || liveCaption.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 86.dp, start = 24.dp, end = 24.dp)
                                .background(Color(0xCC120B16), RoundedCornerShape(18.dp))
                                .border(1.dp, Color(0x66C9A84C), RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = liveStatus,
                                color = Color(0xFFF1D99B),
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp,
                            )
                            if (liveCaption.isNotBlank()) {
                                Text(
                                    text = liveCaption,
                                    color = Color(0xFFF7EEDC),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
