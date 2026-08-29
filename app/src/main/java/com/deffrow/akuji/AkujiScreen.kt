package com.deffrow.akuji

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AkujiPresenceState(val label: String) {
    Ready("READY"),
    Connecting("CONNECTING"),
    Listening("LISTENING"),
    Speaking("SPEAKING"),
    CoreImporting("IMPORTING CORE"),
    Error("ERROR"),
    MicrophoneBlocked("MICROPHONE BLOCKED"),
}

@Composable
fun AkujiApp(
    state: AkujiPresenceState,
    caption: String,
    speechPulse: Int,
    liveActive: Boolean,
    onTalk: () -> Unit,
    onSkills: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val core = remember { AkujiCoreStore(context) }
    var coreState by remember { mutableStateOf(core.hasCore) }
    var localStatus by remember { mutableStateOf<String?>(null) }
    var importingCore by remember { mutableStateOf(false) }

    val coreImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importingCore = true
            localStatus = "Copying AKUJI's core into private on-device storage..."
            val imported = withContext(Dispatchers.IO) { core.import(uris) }
            imported
                .onSuccess { result ->
                    coreState = true
                    localStatus = "AKUJI core updated: ${result.fileCount} file" +
                        if (result.fileCount == 1) "." else "s."
                }
                .onFailure { error ->
                    localStatus = error.message ?: "AKUJI could not import that core."
                }
            importingCore = false
        }
    }

    val effectiveState = if (importingCore) AkujiPresenceState.CoreImporting else state
    val effectiveCaption = localStatus ?: caption

    MaterialTheme {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            AkujiBody(
                state = effectiveState,
                caption = effectiveCaption,
                speechPulse = speechPulse,
                liveActive = liveActive,
                hasCore = coreState,
                onTalk = {
                    localStatus = null
                    onTalk()
                },
                onSkills = onSkills,
                onImportCore = {
                    coreImportLauncher.launch(
                        arrayOf(
                            "text/plain",
                            "application/json",
                            "text/markdown",
                            "application/javascript",
                            "text/html",
                            "application/xml",
                            "application/zip",
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun AkujiBody(
    state: AkujiPresenceState,
    caption: String,
    speechPulse: Int,
    liveActive: Boolean,
    hasCore: Boolean,
    onTalk: () -> Unit,
    onSkills: () -> Unit,
    onImportCore: () -> Unit,
) {
    val voiceMotion = remember { Animatable(0f) }
    LaunchedEffect(speechPulse) {
        if (speechPulse > 0) {
            voiceMotion.snapTo(1f)
            voiceMotion.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "akuji-presence")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == AkujiPresenceState.Speaking) 1.028f else 1.012f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == AkujiPresenceState.Speaking) 520 else 2_300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val aura by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = if (liveActive) 0.9f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (liveActive) 420 else 1_800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aura",
    )

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF07050A))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val pulse = if (state == AkujiPresenceState.Speaking) voiceMotion.value else 0f
                    scaleX = breath + (pulse * 0.004f)
                    scaleY = breath + (pulse * 0.010f)
                    translationY = -(pulse * 7f)
                    rotationZ = (if (speechPulse % 2 == 0) 0.12f else -0.12f) * pulse
                },
        ) {
            Image(
                painter = painterResource(R.drawable.akuji_full_body),
                contentDescription = "AKUJI, the locked DEFF ROW visual identity",
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x33000000),
                        0.48f to Color.Transparent,
                        0.73f to Color(0xAA09050D),
                        1f to Color(0xFF07050A),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "AKUJI",
                        color = Color(0xFFF1D99B),
                        fontWeight = FontWeight.Black,
                        fontSize = 26.sp,
                        letterSpacing = 5.sp,
                    )
                    Text(
                        text = "DEFF ROW // LIVE BODY",
                        color = Color(0xFFCBA3C9),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                    )
                }

                Text(
                    text = state.label,
                    color = Color(0xFFF1D99B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(Color(0x990A080D))
                        .border(1.dp, Color(0x88C9A84C), RoundedCornerShape(100.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = caption.ifBlank {
                        if (liveActive) "AKUJI is listening." else "Tap TALK to speak to AKUJI."
                    },
                    color = Color(0xFFF7EEDC),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xB5120B16))
                        .border(1.dp, Color(0x55C9A84C), RoundedCornerShape(24.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                )

                Spacer(Modifier.height(18.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(104.dp)
                        .alpha(aura)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(Color(0xFFC9A84C), Color.Transparent),
                                ),
                                radius = size.minDimension / 2,
                            )
                        },
                ) {
                    Button(
                        onClick = onTalk,
                        enabled = state != AkujiPresenceState.Connecting &&
                            state != AkujiPresenceState.CoreImporting,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF241128),
                            contentColor = Color(0xFFF1D99B),
                            disabledContainerColor = Color(0xFF151019),
                        ),
                        modifier = Modifier
                            .size(82.dp)
                            .border(2.dp, Color(0xFFC9A84C), CircleShape),
                    ) {
                        Text(
                            text = if (liveActive) "STOP" else "TALK",
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (hasCore) {
                        "Local memory + AKUJI core ready · Live voice uses Google"
                    } else {
                        "Local memory ready · Live voice uses Google"
                    },
                    color = Color(0xFFAA9BAE),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Button(
                        onClick = onImportCore,
                        enabled = state != AkujiPresenceState.CoreImporting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF120B16),
                            contentColor = Color(0xFFF1D99B),
                        ),
                        modifier = Modifier.border(
                            1.dp,
                            Color(0x66C9A84C),
                            RoundedCornerShape(100.dp),
                        ),
                    ) {
                        Text(
                            text = if (hasCore) "UPDATE CORE" else "IMPORT CORE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.8.sp,
                        )
                    }

                    Button(
                        onClick = onSkills,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF120B16),
                            contentColor = Color(0xFFF1D99B),
                        ),
                        modifier = Modifier.border(
                            1.dp,
                            Color(0x66C9A84C),
                            RoundedCornerShape(100.dp),
                        ),
                    ) {
                        Text(
                            text = "SKILLS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.8.sp,
                        )
                    }
                }
            }
        }
    }
}
