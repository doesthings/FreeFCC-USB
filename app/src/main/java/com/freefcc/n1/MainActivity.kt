package com.freefcc.n1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.math.PI

// ═══════════════════════════════════════════════════════════════════════
// Color palette — dark, glowing, signal-themed
// ═══════════════════════════════════════════════════════════════════════

private val BgDark = Color(0xFF070A14)
private val BgMid = Color(0xFF0D1220)
private val BgLight = Color(0xFF121830)
private val CardBg = Color(0xFF10162A)
private val CardBorder = Color(0xFF1C2848)
private val Cyan = Color(0xFF4FC3F7)
private val Green = Color(0xFF34D399)
private val Amber = Color(0xFFF59E0B)
private val Red = Color(0xFFEF4444)
private val Purple = Color(0xFFA78BFA)
private val TextWhite = Color(0xFFF0F4FF)
private val TextGray = Color(0xFF7A85A3)
private val TextDim = Color(0xFF4A5374)

private val BottomNavHeight = 72.dp

// ═══════════════════════════════════════════════════════════════════════
// Activity
// ═══════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {

    private val viewModel: FccViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan, onPrimary = BgDark,
                    background = BgDark, onBackground = TextWhite,
                    surface = CardBg, onSurface = TextWhite,
                    error = Red, secondary = Green, tertiary = Amber
                )
            ) {
                AppRoot(viewModel)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Root layout — 4-page pager with bottom nav
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AppRoot(viewModel: FccViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { 4 }
    val scope = rememberCoroutineScope()

    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(700, easing = EaseOutCubic))
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BgDark, BgMid, BgDark),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY
                )
            )
            .alpha(entrance.value)
    ) {
        // Ambient glow — decorative only
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.radialGradient(
                        listOf(Cyan.copy(0.05f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = 600f
                    )
                )
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true
        ) { page ->
            when (page) {
                0 -> FccPage(state, viewModel)
                1 -> InfoPage(state, viewModel)
                2 -> LogPage(state)
                3 -> AboutPage()
            }
        }

        BottomNavBar(
            currentPage = pagerState.currentPage,
            onPageSelected = { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 1: FCC — the main control surface
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun FccPage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = BottomNavHeight + 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        AppHeader(state)
        Spacer(Modifier.height(28.dp))
        ConnectionPill(state)
        Spacer(Modifier.height(28.dp))

        // --- Main FCC card ---
        GlowCard {
            ModeBadge(state)
            Spacer(Modifier.height(20.dp))

            when {
                state.isBusy -> {
                    ProgressDisplay(state.busyProgress, state.message)
                }
                !state.isConnected -> {
                    BodyText(
                        "Connect your phone to the bottom USB port of the RC-N1/RC-N2/RC-N3, then tap Connect."
                    )
                    Spacer(Modifier.height(20.dp))
                    GlowButton("Connect", Cyan) { viewModel.connect() }
                }
                state.isFccEnabled -> {
                    BodyText("FCC mode is active.", Green)
                    Spacer(Modifier.height(20.dp))
                    GlowButton("Stop FCC Mode", Red) { viewModel.disableFcc() }
                    Spacer(Modifier.height(12.dp))
                    GlowButton("Re-Apply FCC", Cyan, filled = false) { viewModel.enableFcc() }
                }
                else -> {
                    if (state.message.isNotEmpty()) {
                        BodyText(state.message)
                        Spacer(Modifier.height(20.dp))
                    } else {
                        BodyText("Tap the button below to enable FCC mode.")
                        Spacer(Modifier.height(20.dp))
                    }
                    GlowButton("Enable FCC Mode", Cyan) { viewModel.enableFcc() }
                }
            }

            if (state.aircraftSerial.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SerialRow(state.aircraftSerial) { viewModel.probeSerial() }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- 4G card (only when connected) ---
        AnimatedVisibility(
            visible = state.isConnected,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            Column {
                GlowCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SignalWaveIcon(active = state.is4gEnabled, color = Amber, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "4G Mode",
                            color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.is4gEnabled) StatusDot(Green)
                    }
                    Spacer(Modifier.height(12.dp))
                    BodyText(
                        if (state.is4gEnabled) "4G transmission is active." else "Enable 4G transmission on the aircraft.",
                        if (state.is4gEnabled) Green else TextGray
                    )
                    Spacer(Modifier.height(20.dp))
                    if (state.is4gBusy) {
                        ProgressDisplay(state.busyProgress, "Sending 4G frames...")
                    } else {
                        GlowButton(
                            if (state.is4gEnabled) "Turn 4G OFF" else "Turn 4G ON",
                            Amber,
                            filled = state.is4gEnabled
                        ) {
                            if (state.is4gEnabled) viewModel.disable4g() else viewModel.enable4g()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // --- Remote ID card ---
        AnimatedVisibility(
            visible = state.isConnected,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            Column {
                GlowCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.LocationOff,
                            null,
                            tint = if (state.remoteIdDisabled) Red else Purple,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Remote ID",
                            color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.isRidBusy) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = Purple, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    BodyText(
                        if (state.remoteIdDisabled)
                            "Remote ID broadcast is OFF. Drone will not transmit ID/location."
                        else
                            "Remote ID broadcast is ON (compliant). Disable to stop ID transmission.",
                        if (state.remoteIdDisabled) Red.copy(0.85f) else TextGray
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { viewModel.disableRemoteId() },
                            enabled = state.isConnected && !state.isRidBusy && !state.remoteIdDisabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Red, contentColor = BgDark,
                                disabledContainerColor = Red.copy(0.2f),
                                disabledContentColor = Red.copy(0.4f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Red.copy(0.3f)),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text("Disable RID", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Button(
                            onClick = { viewModel.enableRemoteId() },
                            enabled = state.isConnected && !state.isRidBusy && state.remoteIdDisabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Green,
                                disabledContainerColor = Green.copy(0.1f),
                                disabledContentColor = Green.copy(0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.5.dp, Green.copy(0.5f)),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text("Enable RID", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // --- Auto-FCC toggle ---
        Spacer(Modifier.height(16.dp))
        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-FCC", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Automatically connect and enable FCC when the app opens.",
                        color = TextGray, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = state.autoFcc,
                    onCheckedChange = { viewModel.toggleAutoFcc() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Cyan,
                        checkedTrackColor = Cyan.copy(0.3f),
                        uncheckedThumbColor = TextGray,
                        uncheckedTrackColor = BgLight
                    )
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 2: Info
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun InfoPage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = BottomNavHeight + 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        PageTitle("Device Info", Icons.Outlined.Info)
        Spacer(Modifier.height(28.dp))

        GlowCard {
            Text("Connection", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            InfoRow("Controller", state.controllerModel.ifEmpty { "Unknown" })
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            InfoRow(
                "Transport",
                state.transportKind.ifEmpty { "Disconnected" },
                valueColor = if (state.transportKind == "USB") Cyan else if (state.transportKind == "TCP") Green else TextGray
            )
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            InfoRow(
                "Status",
                if (state.isConnected) "Connected" else "Disconnected",
                valueColor = if (state.isConnected) Green else TextGray
            )
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            InfoRow("Aircraft S/N", state.aircraftSerial.ifEmpty { "Not detected" })
        }

        Spacer(Modifier.height(16.dp))

        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Version Info", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = { viewModel.queryDeviceInfo() },
                    enabled = state.isConnected && !state.isQueryingInfo,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (state.isQueryingInfo) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = Cyan, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(Icons.Default.Refresh, "Query", tint = Cyan, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (state.deviceInfo.isNotEmpty()) {
                Text(
                    state.deviceInfo,
                    color = TextGray, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (!state.isConnected) {
                BodyText("Connect to the controller first.", TextDim)
            } else {
                BodyText("Tap the refresh button to query version info.")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Quick reference for which transport is active
        GlowCard {
            Text("Transport Info", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            BodyText(
                if (state.transportKind == "USB")
                    "USB connection to the RC-N1/RC-N2/RC-N3. App runs on your phone, " +
                    "sending DUMPL commands over the USB cable to the controller."
                else if (state.transportKind == "TCP")
                    "TCP loopback at 127.0.0.1:40009. App is running on a smart controller " +
                    "(RC2/RC Pro/RC Plus). For smart controllers, use FreeFCC instead."
                else
                    "No transport connected. Plug your phone into the RC-N1/RC-N2/RC-N3 via USB, then tap Connect on the FCC page.",
                TextGray
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 3: Log
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun LogPage(state: AppState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = BottomNavHeight + 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        PageTitle("Activity Log", Icons.Outlined.History)
        Spacer(Modifier.height(28.dp))

        GlowCard {
            if (state.logMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BodyText("No activity yet.", TextDim)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.logMessages.forEachIndexed { index, entry ->
                        val color = when {
                            entry.contains("enabled", true) ||
                            entry.contains("connected", true) ||
                            entry.contains("restored", true) ||
                            entry.contains("received", true) -> Green

                            entry.contains("fail", true) ||
                            entry.contains("error", true) -> Red

                            entry.contains("Enabling", true) ||
                            entry.contains("Disabling", true) ||
                            entry.contains("Probing", true) ||
                            entry.contains("Querying", true) ||
                            entry.contains("Loaded", true) -> Amber

                            else -> Cyan.copy(0.6f)
                        }
                        if (index > 0) {
                            Spacer(Modifier.height(2.dp))
                            DividerLine(alpha = 0.3f)
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            entry,
                            color = color,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 4: About
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AboutPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = BottomNavHeight + 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        PageTitle("About", Icons.Outlined.Info)
        Spacer(Modifier.height(28.dp))

        GlowCard {
            Text("FreeFCC-N1", color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text("Open-source FCC unlock for DJI RC-N1 / RC-N2", color = Cyan, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            BodyText(
                "A free and open-source Android app that unlocks FCC mode, enables 4G, " +
                "and queries device info on DJI controllers that connect " +
                "via USB cable to your phone (RC-N1, RC-N2). No server. No license. " +
                "No tracking. Just raw DUMPL commands from JSON profile files.",
                TextGray
            )
        }

        Spacer(Modifier.height(16.dp))

        GlowCard {
            Text("How it works", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            BodyText(
                "The app sends DUMPL commands to your DJI controller over the USB cable. " +
                "DUMPL is DJI's internal command protocol. Each command is a small binary " +
                "packet with a magic byte (0x55), a header, a payload, and two CRC checksums. " +
                "The app builds these packets from JSON profile files you can inspect and edit.",
                TextGray
            )
        }

        Spacer(Modifier.height(16.dp))

        GlowCard {
            Text("Supported", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            BodyText("Controllers:", Cyan.copy(0.8f))
            Spacer(Modifier.height(8.dp))
            val supported = listOf(
                "RC-N1 (USB cabled to phone)",
                "RC-N2 (USB cabled to phone)",
                "Direct USB-to-drone (USB-C, fallback)"
            )
            supported.forEach {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Icon(Icons.Filled.Check, null, tint = Green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(it, color = TextGray, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            BodyText("Drones — universal profile covers ALL:", Cyan.copy(0.8f))
            Spacer(Modifier.height(8.dp))
            val drones = listOf(
                "Mini 3 (wm163) — the target of this build",
                "Mini 3 Pro (wm162)",
                "Mini 4 Pro (wa140) / Mini 5 Pro (wa150)",
                "Mini 2 / Mini (Mavic Mini)",
                "Air 3 / Air 3S / Air 2S",
                "Mavic 3 series / Mavic 4 Pro",
                "Avata / Avata 2 / FPV / Flip / Neo",
                "Phantom 4 series"
            )
            drones.forEach {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Icon(Icons.Filled.Check, null, tint = Green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(it, color = TextGray, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        GlowCard {
            Text("Disclaimer", color = Red.copy(0.9f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            BodyText(
                "This software is provided for educational and research purposes only. " +
                "Modifying radio transmission parameters may violate laws in your country. " +
                "In most places, increasing radio power beyond legal limits requires " +
                "authorization from the relevant regulatory authority.\n\n" +
                "You are solely responsible for ensuring your use complies with all applicable " +
                "local, regional, and national laws. If you are unsure whether FCC mode is " +
                "legal where you live, do not use this tool.\n\n" +
                "Not affiliated with DJI. Using this tool may void your warranty and DJI " +
                "Care Refresh coverage.",
                TextGray
            )
            Spacer(Modifier.height(16.dp))
            DividerLine()
            Spacer(Modifier.height(16.dp))
            InfoRow("Version", "1.0")
            Spacer(Modifier.height(12.dp))
            InfoRow("License", "AGPL-3.0")
            Spacer(Modifier.height(12.dp))
            InfoRow("Protocol", "DUMPL")
            Spacer(Modifier.height(12.dp))
            InfoRow("Transports", "USB (RC-N1/N2)")
            Spacer(Modifier.height(12.dp))
            InfoRow("Server", "None (fully offline)")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Shared components
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AppHeader(state: AppState) {
    val glow = rememberInfiniteTransition(label = "hdr")
    val glowAlpha by glow.animateFloat(
        0.5f, 0.9f,
        infiniteRepeatable(tween(2800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "hdrGlow"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(240.dp, 60.dp)
        ) {
            Box(
                Modifier
                    .size(240.dp, 60.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Cyan.copy(glowAlpha * 0.12f), Color.Transparent),
                            radius = 160f
                        )
                    )
            )
            Text(
                "FreeFCC-N1",
                color = Cyan.copy(alpha = glowAlpha),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("v1.0")
                if (state.controllerModel.isNotEmpty()) append(" · ${state.controllerModel}")
                if (state.transportKind.isNotEmpty()) append(" · ${state.transportKind}")
            },
            color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PageTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = TextWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConnectionPill(state: AppState) {
    val (label, color) = when {
        state.status == "connecting" -> "Connecting..." to Amber
        state.isConnected -> "Connected" to Green
        state.status == "error" -> "Error" to Red
        else -> "Disconnected" to TextGray
    }

    val bounce = remember { Animatable(1f) }
    LaunchedEffect(state.isConnected) {
        if (state.isConnected) {
            bounce.snapTo(0.8f)
            bounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    val glowAlpha: Float = if (state.isConnected) {
        val t = rememberInfiniteTransition(label = "pill")
        val a by t.animateFloat(0.1f, 0.25f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pillGlow")
        a
    } else 0f

    Surface(
        color = color.copy(0.1f),
        shape = CircleShape,
        border = BorderStroke(1.dp, color.copy(0.3f)),
        modifier = Modifier
            .padding(4.dp)
            .scale(bounce.value)
            .drawBehind {
                if (glowAlpha > 0f) {
                    drawCircle(color.copy(glowAlpha), radius = size.maxDimension * 0.75f)
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModeBadge(state: AppState) {
    val active = state.isFccEnabled
    val bgBrush = if (active) {
        Brush.horizontalGradient(listOf(Color(0xFF0A2540), Color(0xFF0E3050), Color(0xFF0A2540)))
    } else {
        Brush.horizontalGradient(listOf(BgLight.copy(0.4f), BgLight.copy(0.2f)))
    }

    val checkScale = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            checkScale.snapTo(0f)
            checkScale.animateTo(1.2f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            checkScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        } else {
            checkScale.snapTo(0f)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgBrush)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("MODE", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                if (active) "FCC" else "CE",
                color = if (active) Green else TextWhite,
                fontSize = 30.sp, fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (active) "High-power region active" else "Default region",
                color = if (active) Green.copy(0.7f) else TextGray,
                fontSize = 12.sp
            )
        }
        if (active) {
            Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(44.dp).scale(checkScale.value))
        } else {
            Icon(Icons.Outlined.Radio, null, tint = TextDim, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun ProgressDisplay(progress: Float, label: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(BgLight)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(listOf(Cyan, Green)))
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${(progress * 100).toInt()}%",
            color = TextGray, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BodyText(text: String, color: Color = TextGray) {
    Text(text, color = color, fontSize = 13.sp, lineHeight = 20.sp)
}

@Composable
private fun SerialRow(serial: String, onRefresh: () -> Unit) {
    Surface(
        color = BgLight.copy(0.4f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Filled.Flight, null, tint = Cyan.copy(0.6f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("S/N: ", color = TextGray, fontSize = 12.sp)
            Text(serial, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Refresh, "Refresh", tint = TextGray, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = TextWhite) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextGray, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun DividerLine(alpha: Float = 0.5f) {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CardBorder.copy(alpha)))
}

@Composable
private fun StatusDot(color: Color) {
    val pulse = rememberInfiniteTransition(label = "dot")
    val alpha by pulse.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "dotPulse")
    Box(modifier = Modifier.size(10.dp).background(color.copy(alpha), CircleShape))
}

@Composable
private fun GlowCard(content: @Composable () -> Unit) {
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) { content() }
    }
}

@Composable
private fun GlowButton(
    text: String,
    color: Color,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) color else Color.Transparent,
            contentColor = if (filled) BgDark else color,
            disabledContainerColor = color.copy(0.2f),
            disabledContentColor = color.copy(0.4f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = when {
            !filled && enabled -> BorderStroke(1.5.dp, color.copy(0.6f))
            filled && enabled -> BorderStroke(1.dp, color.copy(0.3f))
            else -> null
        },
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.5.sp)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Animated signal wave icon (decorative)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SignalWaveIcon(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "wavePhase"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerY = h / 2
        val amplitude = if (active) h * 0.25f else h * 0.08f
        val lineColor = if (active) color else color.copy(0.35f)

        val path = androidx.compose.ui.graphics.Path()
        for (x in 0..w.toInt() step 2) {
            val y = centerY + amplitude * sin((x / w).toDouble() * 2.0 * PI + phase.toDouble()).toFloat()
            if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Bottom navigation bar
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun BottomNavBar(
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        Triple("FCC", Icons.Filled.Wifi, Cyan),
        Triple("Info", Icons.Filled.Info, Green),
        Triple("Log", Icons.Filled.History, Amber),
        Triple("About", Icons.Filled.Info, Purple)
    )

    Surface(
        color = BgDark.copy(0.98f),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomNavHeight)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, (label, icon, color) ->
                val selected = currentPage == index

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onPageSelected(index) }
                        .padding(vertical = 8.dp)
                ) {
                    Icon(icon, label, tint = if (selected) color else TextDim, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        color = if (selected) color else TextDim,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (selected) color else Color.Transparent)
                    )
                }
            }
        }
    }
}