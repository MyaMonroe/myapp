package com.deffrow.akuji

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    private val incomingShare = mutableStateOf<AkujiIncomingShare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        incomingShare.value = extractAkujiIncomingShare(applicationContext, intent)

        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance(),
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val scope = rememberCoroutineScope()
            val liveVoice = remember { AkujiLiveVoice(this@MainActivity.applicationContext) }
            val sharedItem = incomingShare.value
            var showSkills by rememberSaveable { mutableStateOf(false) }
            var liveActive by remember { mutableStateOf(false) }
            var liveStatus by remember { mutableStateOf("LIVE OFF") }
            var liveCaption by remember { mutableStateOf("") }
            var pendingSessionContext by remember { mutableStateOf<String?>(null) }

            fun beginLiveVoice(sessionContext: String?) {
                if (liveActive) return
                liveStatus = "CONNECTING LIVE VOICE"
                liveCaption = if (sessionContext.isNullOrBlank()) {
                    "Loading AKUJI skills and waiting for the microphone conversation to become active..."
                } else {
                    "Loading AKUJI skills plus the item you shared..."
                }
                scope.launch {
                    runCatching {
                        liveVoice.start(
                            sessionContext = sessionContext,
                            onInputTranscript = { text ->
                                scope.launch { liveCaption = "YOU: $text" }
                            },
                            onOutputTranscript = { text ->
                                scope.launch { liveCaption = "AKUJI: $text" }
                            },
                        )
                    }.onSuccess {
                        liveActive = true
                        liveStatus = if (sessionContext.isNullOrBlank()) "LIVE LISTENING" else "LIVE + SHARED ITEM"
                        val count = liveVoice.bundledSkillCount
                        liveCaption = if (sessionContext.isNullOrBlank()) {
                            "Mic is active. $count bundled AKUJI skill" +
                                if (count == 1) " is active." else "s are active."
                        } else {
                            "Mic is active. $count bundled skills + your shared item are loaded."
                        }
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
                    beginLiveVoice(pendingSessionContext)
                } else {
                    liveActive = false
                    liveStatus = "MICROPHONE BLOCKED"
                    liveCaption = "AKUJI needs microphone permission for Live voice."
                }
            }

            fun requestLiveVoice(sessionContext: String?) {
                pendingSessionContext = sessionContext
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED

                if (granted) beginLiveVoice(sessionContext)
                else livePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            fun toggleLiveVoice() {
                if (liveActive || liveVoice.isActive) {
                    liveVoice.stop()
                    liveActive = false
                    liveStatus = "LIVE OFF"
                    liveCaption = ""
                    return
                }

                requestLiveVoice(sharedItem?.liveContext)
            }

            fun loadSharedIntoLive() {
                val item = sharedItem ?: return
                if (liveActive || liveVoice.isActive) {
                    liveVoice.stop()
                    liveActive = false
                }
                requestLiveVoice(item.liveContext)
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

                    sharedItem?.let { item ->
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 18.dp, end = 18.dp, bottom = 24.dp)
                                .fillMaxWidth()
                                .background(Color(0xEE120B16), RoundedCornerShape(20.dp))
                                .border(1.dp, Color(0x88C9A84C), RoundedCornerShape(20.dp))
                                .padding(14.dp),
                        ) {
                            Text(
                                text = "SHARED TO AKUJI",
                                color = Color(0xFFF1D99B),
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp,
                                letterSpacing = 1.1.sp,
                            )
                            Text(
                                text = item.title,
                                color = Color(0xFFF7EEDC),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                            Text(
                                text = item.summary,
                                color = Color(0xFFB9ACBC),
                                fontSize = 11.sp,
                                maxLines = 4,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = ::loadSharedIntoLive) {
                                    Text("LOAD IN LIVE", color = Color(0xFFF1D99B), fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { incomingShare.value = null }) {
                                    Text("DISMISS", color = Color(0xFFB9ACBC))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingShare.value = extractAkujiIncomingShare(applicationContext, intent)
    }
}
