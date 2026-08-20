package com.deffrow.akuji

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AkujiBundledSkill(
    val name: String,
    val description: String,
    val sourcePath: String,
)

private fun loadBundledSkills(context: Context): List<AkujiBundledSkill> {
    return context.assets.list("")
        .orEmpty()
        .sorted()
        .mapNotNull { folder ->
            val path = "$folder/SKILL.md"
            runCatching {
                val text = context.assets.open(path).bufferedReader().use { it.readText() }
                val lines = text.lineSequence().toList()
                val name = lines
                    .firstOrNull { it.trimStart().startsWith("name:") }
                    ?.substringAfter("name:")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: folder
                val description = lines
                    .firstOrNull { it.trimStart().startsWith("description:") }
                    ?.substringAfter("description:")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "AKUJI skill"

                AkujiBundledSkill(
                    name = name,
                    description = description,
                    sourcePath = ".qwen/skills/$folder/SKILL.md",
                )
            }.getOrNull()
        }
}

@Composable
fun AkujiSkillsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val skills = remember { loadBundledSkills(context) }

    MaterialTheme {
        Surface(
            color = Color(0xFF07050A),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "AKUJI SKILLS",
                            color = Color(0xFFF1D99B),
                            fontWeight = FontWeight.Black,
                            fontSize = 24.sp,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            text = "PORTABLE MCP / HARNESS SKILLS",
                            color = Color(0xFFCBA3C9),
                            fontSize = 10.sp,
                            letterSpacing = 1.2.sp,
                        )
                    }

                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF241128),
                            contentColor = Color(0xFFF1D99B),
                        ),
                    ) {
                        Text("BACK", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "These are the same SKILL.md files bundled for the Qwen/MCP harness. " +
                        "They are readable inside AKUJI now; live editing and tool assignment will turn on when the harness bridge is connected.",
                    color = Color(0xFFB9ACBC),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF120B16), RoundedCornerShape(18.dp))
                        .border(1.dp, Color(0x55C9A84C), RoundedCornerShape(18.dp))
                        .padding(14.dp),
                )

                Spacer(Modifier.height(14.dp))

                if (skills.isEmpty()) {
                    Text(
                        text = "No bundled skills were found in this build.",
                        color = Color(0xFFF7EEDC),
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(skills, key = { it.sourcePath }) { skill ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF120B16), RoundedCornerShape(20.dp))
                                    .border(1.dp, Color(0x66C9A84C), RoundedCornerShape(20.dp))
                                    .padding(16.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = skill.name.uppercase(),
                                        color = Color(0xFFF1D99B),
                                        fontWeight = FontWeight.Black,
                                        fontSize = 15.sp,
                                    )
                                    Text(
                                        text = "BUNDLED",
                                        color = Color(0xFFBFD9C2),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                    )
                                }

                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = skill.description,
                                    color = Color(0xFFF7EEDC),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = skill.sourcePath,
                                    color = Color(0xFF8F8292),
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
