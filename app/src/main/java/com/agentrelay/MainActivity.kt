package com.agentrelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// iOS-like color constants
private val IOSBackground = Color(0xFFF2F2F7)
private val IOSCardBackground = Color.White
private val IOSBlue = Color(0xFF007AFF)
private val IOSGreen = Color(0xFF34C759)
private val IOSRed = Color(0xFFFF3B30)
private val IOSOrange = Color(0xFFFF9500)
private val IOSGray = Color(0xFF8E8E93)
private val IOSGray2 = Color(0xFFAEAEB2)
private val IOSGray4 = Color(0xFFD1D1D6)
private val IOSGray6 = Color(0xFFF2F2F7)
private val IOSLabel = Color(0xFF000000)
private val IOSSecondaryLabel = Color(0xFF3C3C43).copy(alpha = 0.6f)
private val IOSTertiaryLabel = Color(0xFF3C3C43).copy(alpha = 0.3f)
private val IOSSeparator = Color(0xFF3C3C43).copy(alpha = 0.12f)

class MainActivity : ComponentActivity() {

    private val screenCaptureRequestCode = 100
    internal companion object {
        const val REQUEST_CODE_AUDIO_PERMISSION = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ConversationHistoryManager.init(this)

        setContent {
            AgentRelayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = IOSBackground
                ) {
                    MainScreen(
                        onRequestScreenCapture = ::requestScreenCapture
                    )
                }
            }
        }

        requestPermissions()
        autoRestartServiceIfNeeded()
        // Pre-cache installed apps list in background
        DeviceContextCache.refreshAsync(this)
    }

    override fun onStop() {
        super.onStop()
        ConversationHistoryManager.saveToDisk()
    }

    private fun autoRestartServiceIfNeeded() {
        if (ScreenCaptureService.instance != null) return
        val secureStorage = SecureStorage.getInstance(this)
        if (!secureStorage.hasApiKey()) return
        if (!AutomationService.isServiceEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) return
        }
        requestScreenCapture()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        }
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), screenCaptureRequestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == screenCaptureRequestCode && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_INIT_PROJECTION
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(intent)
            Toast.makeText(this, "Screen capture enabled!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                SecureStorage.getInstance(this).setWakeWordEnabled(true)
                VoiceCommandService.getInstance(this).start()
                Toast.makeText(this, "Wake word enabled!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Microphone permission required for wake word", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun AgentRelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = IOSBlue,
            secondary = IOSGray,
            background = IOSBackground,
            surface = IOSCardBackground,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = IOSLabel,
            onSurface = IOSLabel,
            error = IOSRed,
            surfaceVariant = IOSGray6,
            outline = IOSGray4
        ),
        content = content
    )
}

// ─── Main Screen with Bottom Navigation ───────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onRequestScreenCapture: () -> Unit) {
    val context = LocalContext.current
    val secureStorage = remember { SecureStorage.getInstance(context) }

    var serviceRunning by remember { mutableStateOf(ScreenCaptureService.instance != null) }
    var isServiceOperationLoading by remember { mutableStateOf(false) }
    var overlayPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.canDrawOverlays(context)
            else true
        )
    }
    var accessibilityPermission by remember { mutableStateOf(AutomationService.isServiceEnabled()) }
    var conversationItems by remember { mutableStateOf(listOf<ConversationItem>()) }
    var selectedTab by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            overlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.canDrawOverlays(context) else true
            accessibilityPermission = AutomationService.isServiceEnabled()
            serviceRunning = ScreenCaptureService.instance != null
        }
    }

    DisposableEffect(Unit) {
        val conversationListener: (List<ConversationItem>) -> Unit = { items ->
            conversationItems = items
        }
        ConversationHistoryManager.addListener(conversationListener)
        onDispose {
            ConversationHistoryManager.removeListener(conversationListener)
        }
    }

    Scaffold(
        containerColor = IOSBackground,
        bottomBar = {
            IOSBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // iOS-style large title
            Text(
                text = if (selectedTab == 0) "Agent" else "Settings",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = IOSLabel,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 16.dp)
            )

            when (selectedTab) {
                0 -> AgentTab(
                    serviceRunning = serviceRunning,
                    accessibilityPermission = accessibilityPermission,
                    overlayPermission = overlayPermission,
                    isServiceOperationLoading = isServiceOperationLoading,
                    conversationItems = conversationItems,
                    onClearHistory = { ConversationHistoryManager.clear() },
                    onStartService = {
                        if (!secureStorage.hasApiKey()) {
                            Toast.makeText(context, "Please set API key first", Toast.LENGTH_SHORT).show()
                        } else if (!accessibilityPermission) {
                            Toast.makeText(context, "Enable Accessibility Service in Settings", Toast.LENGTH_SHORT).show()
                        } else if (!overlayPermission) {
                            Toast.makeText(context, "Grant overlay permission in Settings", Toast.LENGTH_SHORT).show()
                        } else {
                            isServiceOperationLoading = true
                            onRequestScreenCapture()
                            coroutineScope.launch {
                                // Poll until service is actually running (up to 10s)
                                var waited = 0
                                while (ScreenCaptureService.instance == null && waited < 10000) {
                                    delay(250)
                                    waited += 250
                                }
                                serviceRunning = ScreenCaptureService.instance != null
                                isServiceOperationLoading = false
                            }
                        }
                    },
                    onStopService = {
                        coroutineScope.launch {
                            isServiceOperationLoading = true
                            withContext(Dispatchers.IO) {
                                context.stopService(Intent(context, ScreenCaptureService::class.java))
                            }
                            // Poll until service is actually stopped (up to 5s)
                            var waited = 0
                            while (ScreenCaptureService.instance != null && waited < 5000) {
                                delay(250)
                                waited += 250
                            }
                            serviceRunning = ScreenCaptureService.instance == null
                            isServiceOperationLoading = false
                        }
                    }
                )
                1 -> SettingsTab(
                    overlayPermission = overlayPermission,
                    accessibilityPermission = accessibilityPermission,
                    onRequestOverlayPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    },
                    onRequestAccessibilityPermission = {
                        try {
                            // Open Accessibility > Installed apps directly
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            // On Samsung/Android 13+, try to go to "Installed apps" section
                            val bundle = android.os.Bundle()
                            bundle.putString(":settings:fragment_args_key", "installed_services")
                            intent.putExtra(":settings:fragment_args_key", "installed_services")
                            intent.putExtra(":settings:show_fragment_args", bundle)
                            context.startActivity(intent)
                            Toast.makeText(
                                context,
                                "Tap 'Installed apps', then find Agent Relay and enable it",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                )
            }
        }
    }
}

// ─── iOS Bottom Tab Bar ───────────────────────────────────────────────────────

@Composable
fun IOSBottomBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Surface(
        color = IOSCardBackground.copy(alpha = 0.94f),
        shadowElevation = 0.dp,
        modifier = Modifier.shadow(
            elevation = 0.5.dp,
            spotColor = Color.Black.copy(alpha = 0.15f)
        )
    ) {
        Column {
            Divider(color = IOSSeparator, thickness = 0.5.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IOSTabItem(
                    icon = if (selectedTab == 0) Icons.Filled.SmartToy else Icons.Outlined.SmartToy,
                    label = "Agent",
                    isSelected = selectedTab == 0,
                    onClick = { onTabSelected(0) }
                )
                IOSTabItem(
                    icon = if (selectedTab == 1) Icons.Filled.Settings else Icons.Outlined.Settings,
                    label = "Settings",
                    isSelected = selectedTab == 1,
                    onClick = { onTabSelected(1) }
                )
            }
        }
    }
}

@Composable
fun IOSTabItem(icon: ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val color = if (isSelected) IOSBlue else IOSGray
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = color, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal)
    }
}

// ─── Agent Tab ────────────────────────────────────────────────────────────────

@Composable
fun AgentTab(
    serviceRunning: Boolean,
    accessibilityPermission: Boolean,
    overlayPermission: Boolean,
    isServiceOperationLoading: Boolean,
    conversationItems: List<ConversationItem>,
    onClearHistory: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Status card at top
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (serviceRunning) IOSGreen.copy(alpha = 0.12f) else IOSGray6),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (serviceRunning) Icons.Default.PlayCircle else Icons.Default.PauseCircle,
                        contentDescription = null,
                        tint = if (serviceRunning) IOSGreen else IOSGray,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (serviceRunning) "Running" else "Stopped",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        color = IOSLabel,
                        lineHeight = 22.sp
                    )
                    Text(
                        if (serviceRunning) "Tap 'Start Agent' in notification"
                        else if (!accessibilityPermission || !overlayPermission) "Grant permissions in Settings"
                        else "Ready to start",
                        fontSize = 14.sp,
                        color = IOSSecondaryLabel,
                        lineHeight = 18.sp
                    )
                }
            }

            Divider(color = IOSSeparator, modifier = Modifier.padding(start = 68.dp))

            // Start/Stop button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isServiceOperationLoading) {
                        if (serviceRunning) onStopService() else onStartService()
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isServiceOperationLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = IOSBlue,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        if (serviceRunning) "Stop Service" else "Start Service",
                        color = if (serviceRunning) IOSRed else IOSBlue,
                        fontWeight = FontWeight.Normal,
                        fontSize = 17.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Activity feed
        if (conversationItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Forum,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = IOSTertiaryLabel
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("No Activity", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = IOSSecondaryLabel)
                Text(
                    "Start the agent to see activity here",
                    fontSize = 15.sp,
                    color = IOSTertiaryLabel,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "RECENT ACTIVITY",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = IOSSecondaryLabel,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Clear",
                    fontSize = 13.sp,
                    color = IOSRed,
                    modifier = Modifier
                        .clickable { onClearHistory() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(conversationItems.reversed()) { item ->
                    ActivityRow(item)
                }
            }
        }
    }
}

// ─── Debug Tap Tool ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugTapTool(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var xText by remember { mutableStateOf("") }
    var yText by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("50") }
    var resultText by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    IOSGroupedCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF5856D6)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Debug Tap", fontSize = 17.sp, color = IOSLabel)
                Text(
                    "Send a tap/long-press at coordinates",
                    fontSize = 13.sp,
                    color = IOSSecondaryLabel
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = IOSGray2,
                modifier = Modifier.size(18.dp)
            )
        }

        if (expanded) {
            Divider(color = IOSSeparator, modifier = Modifier.padding(start = 58.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = xText,
                        onValueChange = { xText = it.filter { c -> c.isDigit() } },
                        label = { Text("X", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IOSBlue,
                            unfocusedBorderColor = IOSGray4,
                            cursorColor = IOSBlue,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = yText,
                        onValueChange = { yText = it.filter { c -> c.isDigit() } },
                        label = { Text("Y", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IOSBlue,
                            unfocusedBorderColor = IOSGray4,
                            cursorColor = IOSBlue,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it.filter { c -> c.isDigit() } },
                        label = { Text("ms", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IOSBlue,
                            unfocusedBorderColor = IOSGray4,
                            cursorColor = IOSBlue,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "ms = hold duration. 50 = normal tap, 500+ = long press",
                    fontSize = 11.sp,
                    color = IOSTertiaryLabel
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val x = xText.toIntOrNull()
                        val y = yText.toIntOrNull()
                        val dur = durationText.toLongOrNull() ?: 50L
                        if (x == null || y == null) {
                            resultText = "Enter valid X and Y"
                            return@Button
                        }
                        val automationService = AutomationService.instance
                        if (automationService == null) {
                            resultText = "Accessibility service not running"
                            return@Button
                        }
                        resultText = "Tapping ($x, $y) for ${dur}ms..."
                        coroutineScope.launch {
                            val startTime = System.currentTimeMillis()
                            val ok = if (dur <= 100) {
                                automationService.performTap(x, y)
                            } else {
                                automationService.performLongPress(x, y, dur)
                            }
                            val elapsed = System.currentTimeMillis() - startTime
                            resultText = if (ok) {
                                "Tap at ($x, $y) ${dur}ms — OK (${elapsed}ms)"
                            } else {
                                "Tap at ($x, $y) ${dur}ms — FAILED (${elapsed}ms)"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5856D6))
                ) {
                    Text("Execute Tap", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                resultText?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = if (it.contains("FAILED")) IOSRed else IOSGreen,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ─── Element Map Parsing & Overlay ─────────────────────────────────────────────

private data class ParsedElement(
    val id: String,
    val type: String,
    val label: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val isClickable: Boolean = false
)

private data class ParsedElementMap(
    val screenWidth: Int,
    val screenHeight: Int,
    val elements: List<ParsedElement>
)

private val elementMapHeaderRegex = Regex("""ELEMENT MAP \((\d+)x(\d+)\)""")
private val elementLineRegex = Regex("""\[([^\]]+)]\s+(\S+)(.*?)\((\d+),(\d+),(\d+),(\d+)\)""")
private val elementTextRegex = Regex(""""([^"]*?)"""")

private fun parseElementMapText(text: String): ParsedElementMap? {
    val elements = mutableListOf<ParsedElement>()
    var screenWidth = 0
    var screenHeight = 0

    for (line in text.lines()) {
        val headerMatch = elementMapHeaderRegex.find(line)
        if (headerMatch != null) {
            screenWidth = headerMatch.groupValues[1].toIntOrNull() ?: 0
            screenHeight = headerMatch.groupValues[2].toIntOrNull() ?: 0
            continue
        }

        val match = elementLineRegex.find(line) ?: continue
        val id = match.groupValues[1]
        val type = match.groupValues[2]
        val middle = match.groupValues[3]
        val label = elementTextRegex.find(middle)?.groupValues?.get(1) ?: id

        elements.add(ParsedElement(
            id = id,
            type = type,
            label = label.ifBlank { id },
            left = match.groupValues[4].toIntOrNull() ?: 0,
            top = match.groupValues[5].toIntOrNull() ?: 0,
            right = match.groupValues[6].toIntOrNull() ?: 0,
            bottom = match.groupValues[7].toIntOrNull() ?: 0,
            isClickable = middle.contains("clickable")
        ))
    }

    if (screenWidth <= 0 || screenHeight <= 0 || elements.isEmpty()) return null
    return ParsedElementMap(screenWidth, screenHeight, elements)
}

@Composable
private fun ElementOverlayImage(
    imageBase64: String,
    parsedMap: ParsedElementMap,
    chosenElementId: String?,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(imageBase64) {
        try {
            val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (e: Exception) { null }
    }
    if (imageBitmap == null) return

    var selectedElement by remember { mutableStateOf<ParsedElement?>(null) }
    val density = LocalDensity.current

    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, IOSGray4, RoundedCornerShape(8.dp))
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val bitmapAspect = imageBitmap.width.toFloat() / imageBitmap.height
            val heightPx = widthPx / bitmapAspect
            val heightDp = with(density) { heightPx.toDp() }

            val scaleX = widthPx / parsedMap.screenWidth
            val scaleY = heightPx / parsedMap.screenHeight

            Image(
                bitmap = imageBitmap,
                contentDescription = "Screenshot with element overlays",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp),
                contentScale = ContentScale.FillWidth
            )

            ElementBoundingBoxes(
                elements = parsedMap.elements,
                chosenElementId = chosenElementId,
                selectedElement = selectedElement,
                scaleX = scaleX,
                scaleY = scaleY,
                heightDp = heightDp,
                onElementTapped = { tapped ->
                    selectedElement = if (tapped == selectedElement) null else tapped
                }
            )
        }

        // Info card for selected element
        selectedElement?.let { el ->
            Spacer(modifier = Modifier.height(6.dp))
            ElementInfoCard(el)
        }
    }
}

@Composable
private fun ElementBoundingBoxes(
    elements: List<ParsedElement>,
    chosenElementId: String?,
    selectedElement: ParsedElement?,
    scaleX: Float,
    scaleY: Float,
    heightDp: androidx.compose.ui.unit.Dp,
    onElementTapped: (ParsedElement?) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            .pointerInput(elements) {
                detectTapGestures { offset ->
                    val candidates = elements.filter { el ->
                        offset.x >= el.left * scaleX && offset.x <= el.right * scaleX &&
                            offset.y >= el.top * scaleY && offset.y <= el.bottom * scaleY
                    }
                    onElementTapped(
                        candidates.minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                    )
                }
            }
    ) {
        for (el in elements) {
            val isChosen = el.id == chosenElementId
            val isSelected = el == selectedElement

            val fillColor = when {
                isSelected -> Color(0x400088FF)
                isChosen -> Color(0x3000CC00)
                el.isClickable -> Color(0x15FF8800)
                else -> Color(0x08888888)
            }
            val borderColor = when {
                isSelected -> Color(0xFF0088FF.toInt())
                isChosen -> Color(0xFF00CC00.toInt())
                el.isClickable -> Color(0x60FF8800)
                else -> Color(0x30888888)
            }

            val topLeft = Offset(el.left * scaleX, el.top * scaleY)
            val boxSize = Size(
                (el.right - el.left) * scaleX,
                (el.bottom - el.top) * scaleY
            )

            drawRect(color = fillColor, topLeft = topLeft, size = boxSize)
            drawRect(
                color = borderColor,
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = if (isChosen || isSelected) 2.5f else 1f)
            )
        }
    }
}

@Composable
private fun ElementInfoCard(el: ParsedElement) {
    Surface(
        color = Color(0xFFF0F4FF),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(IOSBlue.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    el.id,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = IOSBlue,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "${el.type}: ${el.label}",
                    fontSize = 12.sp,
                    color = IOSLabel
                )
                Text(
                    "(${el.left},${el.top}) - (${el.right},${el.bottom})" +
                        if (el.isClickable) "  clickable" else "",
                    fontSize = 10.sp,
                    color = IOSTertiaryLabel,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}

// ─── Activity Row ──────────────────────────────────────────────────────────────

@Composable
fun ActivityRow(item: ConversationItem) {
    var expanded by remember { mutableStateOf(false) }
    var debugExpanded by remember { mutableStateOf(false) }
    val hasDebugData = item.elementMapText != null || item.annotatedScreenshot != null
    val iconColor = when (item.type) {
        ConversationItem.ItemType.ERROR -> IOSRed
        ConversationItem.ItemType.ACTION_EXECUTED -> IOSGreen
        ConversationItem.ItemType.SCREENSHOT_CAPTURED -> IOSOrange
        ConversationItem.ItemType.PLANNING -> Color(0xFF5856D6) // iOS purple
        ConversationItem.ItemType.REASONING -> Color(0xFF34C759) // iOS green for thinking
        else -> IOSBlue
    }
    val icon = when (item.type) {
        ConversationItem.ItemType.SCREENSHOT_CAPTURED -> Icons.Default.PhotoCamera
        ConversationItem.ItemType.API_REQUEST -> Icons.Default.ArrowUpward
        ConversationItem.ItemType.API_RESPONSE -> Icons.Default.SmartToy
        ConversationItem.ItemType.ACTION_EXECUTED -> Icons.Default.CheckCircle
        ConversationItem.ItemType.ERROR -> Icons.Default.ErrorOutline
        ConversationItem.ItemType.PLANNING -> Icons.Default.Psychology
        ConversationItem.ItemType.REASONING -> Icons.Default.AutoAwesome
    }

    Surface(
        color = IOSCardBackground,
        shape = RoundedCornerShape(0.dp)
    ) {
        Column {
            // Main row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(iconColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val isReasoning = item.type == ConversationItem.ItemType.REASONING
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.status,
                            fontSize = 15.sp,
                            color = IOSLabel,
                            maxLines = if (expanded) Int.MAX_VALUE else if (isReasoning) 3 else 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (item.latencyMs != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            val latencyText = if (item.latencyMs >= 1000) {
                                "${String.format("%.1f", item.latencyMs / 1000.0)}s"
                            } else {
                                "${item.latencyMs}ms"
                            }
                            Text(
                                latencyText,
                                fontSize = 11.sp,
                                color = IOSTertiaryLabel,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                    if (isReasoning && !expanded) {
                        // Show progress assessment as subtitle
                        if (item.actionDescription != null) {
                            Text(item.actionDescription, fontSize = 13.sp, color = IOSSecondaryLabel, maxLines = 2)
                        }
                        if (item.response != null) {
                            val stepCount = item.response.lines().size
                            Text("$stepCount steps planned", fontSize = 12.sp, color = IOSTertiaryLabel)
                        }
                    } else if (item.actionDescription != null && !expanded) {
                        Text(item.actionDescription, fontSize = 13.sp, color = IOSSecondaryLabel, maxLines = 1)
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = IOSGray2,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Expanded basic info
            if (expanded) {
                Column(modifier = Modifier.padding(start = 58.dp, end = 16.dp, bottom = 12.dp)) {
                    if (item.type == ConversationItem.ItemType.REASONING) {
                        // Show progress assessment prominently
                        item.actionDescription?.let {
                            Text(it, fontSize = 14.sp, color = IOSSecondaryLabel)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        // Show step breakdown
                        item.response?.let {
                            Text("Planned steps:", fontSize = 12.sp, color = IOSTertiaryLabel, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(it, fontSize = 13.sp, color = IOSLabel, fontFamily = androidx.compose.ui.text.font.FontFamily.Default)
                        }
                    } else {
                    item.actionDescription?.let {
                        Text("Action: $it", fontSize = 13.sp, color = IOSBlue)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    item.response?.let {
                        Text("Response: $it", fontSize = 13.sp, color = IOSSecondaryLabel)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    item.prompt?.let {
                        Text("Prompt: $it", fontSize = 13.sp, color = IOSSecondaryLabel)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    } // end else (non-REASONING)

                    // Chosen element + coordinates (for ACTION_EXECUTED)
                    if (item.chosenElementId != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .background(IOSBlue.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    item.chosenElementId,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = IOSBlue,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            if (item.chosenElementText?.isNotBlank() == true) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "\"${item.chosenElementText}\"",
                                    fontSize = 13.sp,
                                    color = IOSSecondaryLabel,
                                    maxLines = 1
                                )
                            }
                        }
                        if (item.clickX != null && item.clickY != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Tap at (${item.clickX}, ${item.clickY})",
                                fontSize = 12.sp,
                                color = IOSTertiaryLabel,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }

                    // Annotated screenshot (for ACTION_EXECUTED)
                    item.annotatedScreenshot?.let { annotated ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap Location",
                            fontSize = 12.sp,
                            color = IOSTertiaryLabel,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val annotatedBitmap = remember(annotated) {
                            try {
                                val bytes = Base64.decode(annotated, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } catch (e: Exception) { null }
                        }
                        if (annotatedBitmap != null) {
                            Image(
                                bitmap = annotatedBitmap,
                                contentDescription = "Annotated screenshot showing tap location",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, IOSGray4, RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    // Plain screenshot (for SCREENSHOT_CAPTURED)
                    item.screenshot?.let { screenshot ->
                        Text(
                            "Screenshot (${item.screenshotWidth}x${item.screenshotHeight})",
                            fontSize = 12.sp, color = IOSTertiaryLabel, fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val imageBitmap = remember(screenshot) {
                            try {
                                val imageBytes = Base64.decode(screenshot, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.asImageBitmap()
                            } catch (e: Exception) { null }
                        }
                        if (imageBitmap != null) {
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "Screenshot",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }

            // Debug row — Element Tree (separate expandable)
            if (expanded && hasDebugData) {
                Divider(color = IOSSeparator, modifier = Modifier.padding(start = 58.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { debugExpanded = !debugExpanded }
                        .padding(start = 58.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        tint = IOSGray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Element Tree",
                        fontSize = 13.sp,
                        color = IOSSecondaryLabel,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${item.elementMapText?.lines()?.count { it.trimStart().startsWith("[") } ?: 0} elements",
                        fontSize = 12.sp,
                        color = IOSTertiaryLabel
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        if (debugExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = IOSGray2,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (debugExpanded && item.elementMapText != null) {
                    Column(
                        modifier = Modifier
                            .padding(start = 58.dp, end = 16.dp, bottom = 12.dp)
                    ) {
                        // Interactive bounding box overlay on screenshot
                        val parsedMap = remember(item.elementMapText) {
                            parseElementMapText(item.elementMapText)
                        }
                        val overlayImage = item.screenshot ?: item.annotatedScreenshot
                        if (parsedMap != null && overlayImage != null) {
                            Text(
                                "Tap an element to inspect",
                                fontSize = 11.sp,
                                color = IOSTertiaryLabel,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            ElementOverlayImage(
                                imageBase64 = overlayImage,
                                parsedMap = parsedMap,
                                chosenElementId = item.chosenElementId,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Text list (collapsible)
                        var textListExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { textListExpanded = !textListExpanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Raw Element List",
                                fontSize = 11.sp,
                                color = IOSTertiaryLabel,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (textListExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = IOSGray2,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        if (textListExpanded) {
                            Surface(
                                color = Color(0xFFF8F8FA),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val scrollState = rememberScrollState()
                                Column(
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .heightIn(max = 300.dp)
                                        .verticalScroll(scrollState)
                                ) {
                                    item.elementMapText.lines().forEach { line ->
                                        val isChosenLine = item.chosenElementId != null &&
                                                line.contains("[${item.chosenElementId}]")
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            if (isChosenLine) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(IOSBlue.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        line,
                                                        fontSize = 10.sp,
                                                        color = IOSBlue,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                        maxLines = 2
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    line,
                                                    fontSize = 10.sp,
                                                    color = IOSSecondaryLabel,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    maxLines = 2
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

            Divider(color = IOSSeparator, modifier = Modifier.padding(start = 58.dp))
        }
    }
}

// ─── Settings Tab ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    overlayPermission: Boolean,
    accessibilityPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit
) {
    val context = LocalContext.current
    val secureStorage = remember { SecureStorage.getInstance(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        // ── Permissions Section ──
        IOSSectionHeader("PERMISSIONS")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            IOSSettingsRow(
                icon = Icons.Default.Accessibility,
                iconBackground = IOSBlue,
                title = "Accessibility Service",
                subtitle = if (accessibilityPermission) "Enabled" else "Tap to open Installed Apps",
                trailing = {
                    if (accessibilityPermission) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = IOSGreen, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = IOSGray2, modifier = Modifier.size(22.dp))
                    }
                },
                onClick = onRequestAccessibilityPermission
            )
            Divider(color = IOSSeparator, modifier = Modifier.padding(start = 58.dp))
            IOSSettingsRow(
                icon = Icons.Default.Layers,
                iconBackground = IOSOrange,
                title = "Display Over Apps",
                subtitle = if (overlayPermission) "Enabled" else "Required for overlays",
                trailing = {
                    if (overlayPermission) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = IOSGreen, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = IOSGray2, modifier = Modifier.size(22.dp))
                    }
                },
                onClick = onRequestOverlayPermission
            )
        }

        if (!accessibilityPermission) {
            Text(
                "Go to Accessibility > Installed apps > Agent Relay, then toggle on.",
                fontSize = 13.sp,
                color = IOSSecondaryLabel,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
                lineHeight = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── API Keys Section ──
        IOSSectionHeader("API KEYS")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var claudeKey by remember { mutableStateOf(secureStorage.getClaudeApiKey() ?: "") }
            var openaiKey by remember { mutableStateOf(secureStorage.getOpenAIApiKey() ?: "") }
            var geminiKey by remember { mutableStateOf(secureStorage.getGeminiApiKey() ?: "") }

            Column(modifier = Modifier.padding(16.dp)) {
                // Claude
                Text("Anthropic (Claude)", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = IOSSecondaryLabel)
                Spacer(modifier = Modifier.height(6.dp))
                ApiKeyField(
                    value = claudeKey,
                    onValueChange = { claudeKey = it },
                    placeholder = "sk-ant-api03-...",
                    isValid = claudeKey.isEmpty() || secureStorage.isValidClaudeKey(claudeKey),
                    onSave = {
                        if (claudeKey.isEmpty() || secureStorage.isValidClaudeKey(claudeKey)) {
                            secureStorage.saveClaudeApiKey(claudeKey)
                            Toast.makeText(context, "Claude key saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid Claude key format", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // OpenAI
                Text("OpenAI", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = IOSSecondaryLabel)
                Spacer(modifier = Modifier.height(6.dp))
                ApiKeyField(
                    value = openaiKey,
                    onValueChange = { openaiKey = it },
                    placeholder = "sk-proj-...",
                    isValid = openaiKey.isEmpty() || secureStorage.isValidOpenAIKey(openaiKey),
                    onSave = {
                        if (openaiKey.isEmpty() || secureStorage.isValidOpenAIKey(openaiKey)) {
                            secureStorage.saveOpenAIApiKey(openaiKey)
                            Toast.makeText(context, "OpenAI key saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid OpenAI key format", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Gemini
                Text("Google (Gemini)", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = IOSSecondaryLabel)
                Spacer(modifier = Modifier.height(6.dp))
                ApiKeyField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    placeholder = "AIza...",
                    isValid = geminiKey.isEmpty() || secureStorage.isValidGeminiKey(geminiKey),
                    onSave = {
                        if (geminiKey.isEmpty() || secureStorage.isValidGeminiKey(geminiKey)) {
                            secureStorage.saveGeminiApiKey(geminiKey)
                            Toast.makeText(context, "Gemini key saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid Gemini key format", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
        Text(
            "Set API keys for the providers you want to use. The app picks the right key based on the selected model.",
            fontSize = 13.sp,
            color = IOSSecondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Fast Model Section ──
        IOSSectionHeader("FAST MODEL")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var selectedModel by remember { mutableStateOf(secureStorage.getModel()) }
            var fastExpanded by remember { mutableStateOf(false) }
            val fastModelOptions = listOf(
                "claude-haiku-4-5-20251001" to "Claude Haiku 4.5",
                "claude-sonnet-4-5" to "Claude Sonnet 4.5",
                "gpt-4.1-nano" to "GPT-4.1 Nano",
                "gpt-4.1-mini" to "GPT-4.1 Mini",
                "gpt-4.1" to "GPT-4.1",
                "gpt-4o-mini" to "GPT-4o Mini",
                "gpt-4o" to "GPT-4o",
                "gemini-2.5-pro" to "Gemini 2.5 Pro",
                "gemini-2.5-flash" to "Gemini 2.5 Flash",
                "gemini-2.0-flash" to "Gemini 2.0 Flash"
            )
            val selectedFastName = fastModelOptions.find { it.first == selectedModel }?.second ?: selectedModel

            ExposedDropdownMenuBox(
                expanded = fastExpanded,
                onExpandedChange = { fastExpanded = it }
            ) {
                IOSSettingsRow(
                    title = "Model",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedFastName, color = IOSSecondaryLabel, fontSize = 15.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                if (fastExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = IOSSecondaryLabel,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    onClick = { fastExpanded = true },
                    modifier = Modifier.menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = fastExpanded,
                    onDismissRequest = { fastExpanded = false }
                ) {
                    fastModelOptions.forEach { (modelId, modelName) ->
                        DropdownMenuItem(
                            text = { Text(modelName) },
                            onClick = {
                                selectedModel = modelId
                                secureStorage.saveModel(modelId)
                                fastExpanded = false
                            },
                            trailingIcon = if (selectedModel == modelId) {
                                { Icon(Icons.Default.Check, contentDescription = null, tint = IOSBlue, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
            }
        }
        Text(
            "Used for each step of task execution. Faster models reduce latency per action.",
            fontSize = 13.sp,
            color = IOSSecondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Thinking Model Section ──
        IOSSectionHeader("THINKING MODEL")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var selectedThinkingModel by remember { mutableStateOf(secureStorage.getPlanningModel()) }
            var thinkingExpanded by remember { mutableStateOf(false) }
            val thinkingModelOptions = listOf(
                "claude-opus-4-6" to "Claude Opus 4.6",
                "claude-sonnet-4-6" to "Claude Sonnet 4.6",
                "gpt-4.1" to "GPT-4.1",
                "gpt-4o" to "GPT-4o",
                "o4-mini" to "o4-mini",
                "gemini-2.5-pro" to "Gemini 2.5 Pro",
                "gemini-2.0-flash" to "Gemini 2.0 Flash"
            )
            val selectedThinkingName = thinkingModelOptions.find { it.first == selectedThinkingModel }?.second ?: selectedThinkingModel

            ExposedDropdownMenuBox(
                expanded = thinkingExpanded,
                onExpandedChange = { thinkingExpanded = it }
            ) {
                IOSSettingsRow(
                    title = "Model",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedThinkingName, color = IOSSecondaryLabel, fontSize = 15.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                if (thinkingExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = IOSSecondaryLabel,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    onClick = { thinkingExpanded = true },
                    modifier = Modifier.menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = thinkingExpanded,
                    onDismissRequest = { thinkingExpanded = false }
                ) {
                    thinkingModelOptions.forEach { (modelId, modelName) ->
                        DropdownMenuItem(
                            text = { Text(modelName) },
                            onClick = {
                                selectedThinkingModel = modelId
                                secureStorage.savePlanningModel(modelId)
                                thinkingExpanded = false
                            },
                            trailingIcon = if (selectedThinkingModel == modelId) {
                                { Icon(Icons.Default.Check, contentDescription = null, tint = IOSBlue, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
            }
        }
        Text(
            "Used for strategic planning at task start and recovery from repeated failures.",
            fontSize = 13.sp,
            color = IOSSecondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Screenshot Quality Section ──
        IOSSectionHeader("SCREENSHOT QUALITY")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var selectedQuality by remember { mutableStateOf(secureStorage.getScreenshotQuality()) }
            val qualityOptions = listOf(
                -1 to "Auto",
                15 to "Low (fastest, smallest)",
                30 to "Medium"
            )

            qualityOptions.forEachIndexed { index, (quality, name) ->
                IOSSettingsRow(
                    title = name,
                    trailing = {
                        if (selectedQuality == quality) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = IOSBlue, modifier = Modifier.size(20.dp))
                        }
                    },
                    onClick = {
                        selectedQuality = quality
                        secureStorage.saveScreenshotQuality(quality)
                    }
                )
                if (index < qualityOptions.lastIndex) {
                    Divider(color = IOSSeparator, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Interface Section ──
        IOSSectionHeader("INTERFACE")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var bubbleEnabled by remember { mutableStateOf(secureStorage.getFloatingBubbleEnabled()) }

            IOSSettingsRow(
                icon = Icons.Default.Circle,
                iconBackground = Color(0xFFCC9B6D),
                title = "Floating Bubble",
                subtitle = if (bubbleEnabled) "Tap to start agent" else "Disabled",
                trailing = {
                    Switch(
                        checked = bubbleEnabled,
                        onCheckedChange = {
                            bubbleEnabled = it
                            secureStorage.setFloatingBubbleEnabled(it)
                            // Show/hide immediately if service is running
                            if (it && ScreenCaptureService.instance != null) {
                                FloatingBubble.getInstance(context).show()
                            } else {
                                FloatingBubble.getInstance(context).hide()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IOSGreen
                        )
                    )
                }
            )
        }
        Text(
            "Shows a small draggable circle on screen to quickly open the agent.",
            fontSize = 13.sp,
            color = IOSSecondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Voice Control Section ──
        IOSSectionHeader("VOICE CONTROL")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var wakeWordEnabled by remember { mutableStateOf(secureStorage.getWakeWordEnabled()) }
            val activity = context as? Activity

            IOSSettingsRow(
                icon = Icons.Default.Mic,
                iconBackground = Color(0xFFFF9500),
                title = "Wake Word",
                subtitle = if (wakeWordEnabled) "Say 'Hey Relay' to start a task" else "Disabled",
                trailing = {
                    Switch(
                        checked = wakeWordEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                // Check RECORD_AUDIO permission
                                val hasPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    wakeWordEnabled = true
                                    secureStorage.setWakeWordEnabled(true)
                                    VoiceCommandService.getInstance(context).start()
                                } else {
                                    // Request permission
                                    activity?.let {
                                        ActivityCompat.requestPermissions(
                                            it,
                                            arrayOf(Manifest.permission.RECORD_AUDIO),
                                            MainActivity.REQUEST_CODE_AUDIO_PERMISSION
                                        )
                                    }
                                }
                            } else {
                                wakeWordEnabled = false
                                secureStorage.setWakeWordEnabled(false)
                                VoiceCommandService.getInstance(context).stop()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IOSGreen
                        )
                    )
                }
            )
        }
        Text(
            "Continuously listens for 'Hey Relay' to start voice task input. Requires microphone permission.",
            fontSize = 13.sp,
            color = IOSSecondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Agent Behavior Section ──
        val agentCoroutineScope = rememberCoroutineScope()
        var interventionCount by remember { mutableStateOf<Int?>(null) }

        // Load intervention count
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try {
                    val db = com.agentrelay.intervention.InterventionDatabase.getInstance(context)
                    interventionCount = db.interventionDao().getCount()
                } catch (_: Exception) {}
            }
        }

        IOSSectionHeader("AGENT BEHAVIOR")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var blockTouchEnabled by remember { mutableStateOf(secureStorage.getBlockTouchDuringAgent()) }
            var interventionTrackingEnabled by remember { mutableStateOf(secureStorage.getInterventionTrackingEnabled()) }
            var clarificationPromptsEnabled by remember { mutableStateOf(secureStorage.getClarificationPromptsEnabled()) }

            IOSSettingsRow(
                icon = Icons.Default.TouchApp,
                iconBackground = IOSRed,
                title = "Block Touch During Agent",
                subtitle = if (blockTouchEnabled) "Prevents accidental taps" else "Disabled",
                trailing = {
                    Switch(
                        checked = blockTouchEnabled,
                        onCheckedChange = {
                            blockTouchEnabled = it
                            secureStorage.setBlockTouchDuringAgent(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IOSGreen
                        )
                    )
                }
            )

            Divider(color = IOSSeparator, modifier = Modifier.padding(start = 58.dp))

            IOSSettingsRow(
                icon = Icons.Default.TrackChanges,
                iconBackground = Color(0xFF5856D6),
                title = "Track User Interventions",
                subtitle = if (interventionTrackingEnabled) "Records manual corrections" else "Disabled",
                trailing = {
                    Switch(
                        checked = interventionTrackingEnabled,
                        onCheckedChange = {
                            interventionTrackingEnabled = it
                            secureStorage.setInterventionTrackingEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IOSGreen
                        )
                    )
                }
            )

            Divider(color = IOSSeparator, modifier = Modifier.padding(start = 58.dp))

            IOSSettingsRow(
                icon = Icons.Default.QuestionAnswer,
                iconBackground = Color(0xFFFF9500),
                title = "Clarification Prompts",
                subtitle = if (clarificationPromptsEnabled) "Shows alternative paths" else "Disabled",
                trailing = {
                    Switch(
                        checked = clarificationPromptsEnabled,
                        onCheckedChange = {
                            clarificationPromptsEnabled = it
                            secureStorage.setClarificationPromptsEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IOSGreen
                        )
                    )
                }
            )

        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Agent Log (unified trace + interventions) ──
        var logExpanded by remember { mutableStateOf(false) }
        var logFilter by remember { mutableStateOf("all") } // "all", "trace", "interventions"
        var traceEvents by remember { mutableStateOf<List<com.agentrelay.intervention.AgentTraceEvent>>(emptyList()) }
        var interventionEvents by remember { mutableStateOf<List<com.agentrelay.intervention.UserIntervention>>(emptyList()) }
        var traceCount by remember { mutableStateOf(0) }

        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            IOSSettingsRow(
                icon = Icons.Default.Timeline,
                iconBackground = IOSOrange,
                title = "Agent Log",
                subtitle = "${interventionCount ?: 0} interventions · $traceCount trace events",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Export",
                            color = IOSBlue,
                            fontSize = 15.sp,
                            modifier = Modifier.clickable {
                                agentCoroutineScope.launch {
                                    try {
                                        val db = com.agentrelay.intervention.InterventionDatabase.getInstance(context)
                                        val interventions = withContext(Dispatchers.IO) { db.interventionDao().exportAll() }
                                        val traces = withContext(Dispatchers.IO) { db.agentTraceDao().exportAll() }
                                        val clarifications = withContext(Dispatchers.IO) { db.userClarificationDao().exportAll() }
                                        val gson = com.google.gson.Gson()
                                        val exportData = mapOf("interventions" to interventions, "trace" to traces, "clarifications" to clarifications)
                                        val json = gson.toJson(exportData)
                                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                                            android.os.Environment.DIRECTORY_DOWNLOADS
                                        )
                                        val file = java.io.File(downloadsDir, "agent_log_${System.currentTimeMillis()}.json")
                                        file.writeText(json)
                                        Toast.makeText(context, "Exported ${interventions.size + traces.size} events to Downloads", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = if (logExpanded) "Collapse" else "Expand",
                            tint = IOSGray2,
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(if (logExpanded) 180f else 0f)
                        )
                    }
                },
                onClick = {
                    logExpanded = !logExpanded
                    if (logExpanded) {
                        agentCoroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    val db = com.agentrelay.intervention.InterventionDatabase.getInstance(context)
                                    interventionEvents = db.interventionDao().getRecent(100)
                                    traceEvents = db.agentTraceDao().getRecent(200)
                                    traceCount = db.agentTraceDao().getCount()
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            )

            // Load counts on first render
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    try {
                        val db = com.agentrelay.intervention.InterventionDatabase.getInstance(context)
                        traceCount = db.agentTraceDao().getCount()
                    } catch (_: Exception) {}
                }
            }

            if (logExpanded) {
                Column(modifier = Modifier.animateContentSize()) {
                    // Filter tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("all" to "All", "trace" to "Trace", "interventions" to "Interventions").forEach { (key, label) ->
                            val selected = logFilter == key
                            Text(
                                label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) Color.White else IOSBlue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) IOSBlue else IOSBlue.copy(alpha = 0.1f))
                                    .clickable { logFilter = key }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Build unified timeline
                    data class TimelineItem(
                        val timestamp: Long,
                        val intervention: com.agentrelay.intervention.UserIntervention? = null,
                        val trace: com.agentrelay.intervention.AgentTraceEvent? = null
                    )

                    val timelineItems = remember(logFilter, interventionEvents, traceEvents) {
                        val items = mutableListOf<TimelineItem>()
                        if (logFilter != "trace") {
                            interventionEvents.forEach { items.add(TimelineItem(it.timestamp, intervention = it)) }
                        }
                        if (logFilter != "interventions") {
                            traceEvents.forEach { items.add(TimelineItem(it.timestamp, trace = it)) }
                        }
                        items.sortedByDescending { it.timestamp }
                    }

                    if (timelineItems.isEmpty()) {
                        Text(
                            "No events recorded yet",
                            fontSize = 14.sp,
                            color = IOSSecondaryLabel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        val dateFormat = remember { java.text.SimpleDateFormat("MMM d, h:mm:ss a", java.util.Locale.getDefault()) }
                        Column(
                            modifier = Modifier
                                .heightIn(max = 500.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            timelineItems.forEachIndexed { index, item ->
                                if (item.intervention != null) {
                                    InterventionRow(
                                        intervention = item.intervention,
                                        dateFormat = dateFormat,
                                        onDelete = {
                                            agentCoroutineScope.launch {
                                                withContext(Dispatchers.IO) {
                                                    try {
                                                        val db = com.agentrelay.intervention.InterventionDatabase.getInstance(context)
                                                        db.interventionDao().deleteById(item.intervention.id)
                                                    } catch (_: Exception) {}
                                                }
                                                interventionEvents = interventionEvents.filter { it.id != item.intervention.id }
                                                interventionCount = (interventionCount ?: 1) - 1
                                            }
                                        }
                                    )
                                } else if (item.trace != null) {
                                    TraceEventRow(
                                        event = item.trace,
                                        dateFormat = dateFormat,
                                        onDelete = {
                                            agentCoroutineScope.launch {
                                                withContext(Dispatchers.IO) {
                                                    try {
                                                        val db = com.agentrelay.intervention.InterventionDatabase.getInstance(context)
                                                        db.agentTraceDao().deleteById(item.trace.id)
                                                    } catch (_: Exception) {}
                                                }
                                                traceEvents = traceEvents.filter { it.id != item.trace.id }
                                                traceCount = maxOf(0, traceCount - 1)
                                            }
                                        }
                                    )
                                }
                                if (index < timelineItems.lastIndex) {
                                    Divider(color = IOSSeparator, modifier = Modifier.padding(start = 16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Unified log of agent trace events (planning, steps, errors) and user interventions. Swipe left to delete entries.",
            fontSize = 13.sp,
            color = IOSSecondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Screen Recording Section ──
        IOSSectionHeader("SCREEN RECORDING")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var recordingEnabled by remember { mutableStateOf(secureStorage.getScreenRecordingEnabled()) }

            IOSSettingsRow(
                icon = Icons.Default.Videocam,
                iconBackground = IOSRed,
                title = "Record Trajectories",
                subtitle = if (recordingEnabled) "Saves video during agent runs" else "Disabled",
                trailing = {
                    Switch(
                        checked = recordingEnabled,
                        onCheckedChange = {
                            recordingEnabled = it
                            secureStorage.setScreenRecordingEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IOSGreen
                        )
                    )
                }
            )

            Divider(color = IOSSeparator, modifier = Modifier.padding(start = 58.dp))

            IOSSettingsRow(
                icon = Icons.Default.FolderOpen,
                iconBackground = IOSBlue,
                title = "Open Recordings",
                subtitle = run {
                    val dir = ScreenRecorder.getRecordingsDir()
                    val count = dir.listFiles()?.count { it.extension == "mp4" } ?: 0
                    if (count > 0) "$count recording${if (count != 1) "s" else ""}" else "No recordings yet"
                },
                trailing = {
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = IOSGray2, modifier = Modifier.size(22.dp))
                },
                onClick = {
                    val dir = ScreenRecorder.getRecordingsDir()
                    dir.mkdirs()
                    // Open the recordings folder in the system file manager
                    val opened = try {
                        // Use Documents UI content URI for the folder
                        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Movies%2FAgentRelay")
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = uri
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        true
                    } catch (e: Exception) {
                        false
                    }
                    if (!opened) {
                        // Fallback: open video gallery
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Recordings saved at: Movies/AgentRelay/", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
        Text(
            "Records the full screen during agent runs. Videos saved to Movies/AgentRelay/.",
            fontSize = 13.sp,
            color = IOSSecondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Semantic Pipeline Section ──
        IOSSectionHeader("SEMANTIC PIPELINE")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var verificationEnabled by remember { mutableStateOf(secureStorage.getVerificationEnabled()) }
            var screenshotMode by remember { mutableStateOf(secureStorage.getScreenshotMode()) }

            IOSSettingsRow(
                icon = Icons.Default.Verified,
                iconBackground = IOSGreen,
                title = "Pre-Execution Verification",
                subtitle = if (verificationEnabled) "Uses Haiku to verify UI" else "Disabled",
                trailing = {
                    Switch(
                        checked = verificationEnabled,
                        onCheckedChange = {
                            verificationEnabled = it
                            secureStorage.setVerificationEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IOSGreen
                        )
                    )
                }
            )

            Divider(color = IOSSeparator, modifier = Modifier.padding(start = 58.dp))

            IOSSettingsRow(
                icon = Icons.Default.PhotoCamera,
                iconBackground = IOSOrange,
                title = "Screenshots",
                subtitle = when (screenshotMode) {
                    com.agentrelay.models.ScreenshotMode.ON -> "Always send visual context"
                    com.agentrelay.models.ScreenshotMode.AUTO -> "Adaptive — skips when element map is rich"
                    com.agentrelay.models.ScreenshotMode.OFF -> "Structured data only"
                },
                trailing = {}
            )
            // Segmented control for screenshot mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 58.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                val options = listOf(
                    com.agentrelay.models.ScreenshotMode.ON to "On",
                    com.agentrelay.models.ScreenshotMode.AUTO to "Auto",
                    com.agentrelay.models.ScreenshotMode.OFF to "Off"
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = Color(0xFFE5E5EA),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(2.dp)
                ) {
                    Row {
                        options.forEach { (mode, label) ->
                            val isSelected = screenshotMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        color = if (isSelected) Color.White else Color.Transparent,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        screenshotMode = mode
                                        secureStorage.setScreenshotMode(mode)
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) Color.Black else Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Developer Tools Section ──
        IOSSectionHeader("DEVELOPER TOOLS")
        DebugTapTool(modifier = Modifier.padding(horizontal = 16.dp))

        Spacer(modifier = Modifier.height(24.dp))

        // ── Planning Agent Section ──
        IOSSectionHeader("PLANNING AGENT")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var planningEnabled by remember { mutableStateOf(secureStorage.getPlanningEnabled()) }

            IOSSettingsRow(
                icon = Icons.Default.Psychology,
                iconBackground = Color(0xFF5856D6),
                title = "Planning Agent",
                subtitle = if (planningEnabled) "Uses thinking model" else "Disabled",
                trailing = {
                    Switch(
                        checked = planningEnabled,
                        onCheckedChange = {
                            planningEnabled = it
                            secureStorage.setPlanningEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IOSGreen
                        )
                    )
                }
            )
        }
        Text(
            "Uses the thinking model to plan strategies and recover from failures.",
            fontSize = 13.sp,
            color = IOSSecondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── OCR Section ──
        IOSSectionHeader("OCR (OPTIONAL)")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var ocrEnabled by remember { mutableStateOf(secureStorage.getOcrEnabled()) }
            var googleVisionKey by remember { mutableStateOf(secureStorage.getGoogleVisionApiKey() ?: "") }
            var replicateToken by remember { mutableStateOf(secureStorage.getReplicateApiToken() ?: "") }

            IOSSettingsRow(
                icon = Icons.Default.TextFields,
                iconBackground = IOSOrange,
                title = "Enable OCR",
                subtitle = "For WebViews and custom UIs",
                trailing = {
                    Switch(
                        checked = ocrEnabled,
                        onCheckedChange = {
                            ocrEnabled = it
                            secureStorage.setOcrEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IOSGreen
                        )
                    )
                }
            )

            if (ocrEnabled) {
                Divider(color = IOSSeparator, modifier = Modifier.padding(start = 58.dp))
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Google Vision API Key", fontSize = 13.sp, color = IOSSecondaryLabel)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = googleVisionKey,
                        onValueChange = { googleVisionKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("AIza...", color = IOSTertiaryLabel) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IOSBlue,
                            unfocusedBorderColor = IOSGray4,
                            cursorColor = IOSBlue,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            secureStorage.saveGoogleVisionApiKey(googleVisionKey)
                            Toast.makeText(context, "Google Vision key saved", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = IOSBlue)
                    ) {
                        Text("Save", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Replicate API Token (fallback)", fontSize = 13.sp, color = IOSSecondaryLabel)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = replicateToken,
                        onValueChange = { replicateToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("r8_...", color = IOSTertiaryLabel) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IOSBlue,
                            unfocusedBorderColor = IOSGray4,
                            cursorColor = IOSBlue,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            secureStorage.saveReplicateApiToken(replicateToken)
                            Toast.makeText(context, "Replicate token saved", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = IOSBlue)
                    ) {
                        Text("Save", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }
        }
        Text(
            "Adds text recognition for WebViews, games, and custom UIs where the accessibility tree is empty. Adds latency (~1-2s per step) and requires a separate API key. Not needed for most native apps.",
            fontSize = 13.sp,
            color = IOSSecondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Build Info Section ──
        IOSSectionHeader("BUILD INFO")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            IOSSettingsRow(
                icon = Icons.Default.Schedule,
                iconBackground = IOSGray2,
                title = "Last Deployed",
                subtitle = BuildConfig.BUILD_TIMESTAMP
            )
        }
    }
}

// ─── API Key Field Component ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isValid: Boolean,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder, color = IOSTertiaryLabel) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            isError = !isValid,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = IOSBlue,
                unfocusedBorderColor = IOSGray4,
                errorBorderColor = IOSRed,
                cursorColor = IOSBlue,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onSave,
            modifier = Modifier.height(44.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = IOSBlue)
        ) {
            Text("Save", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

// ─── iOS-style Reusable Components ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterventionRow(
    intervention: com.agentrelay.intervention.UserIntervention,
    dateFormat: java.text.SimpleDateFormat,
    onDelete: () -> Unit
) {
    val dismissState = rememberDismissState(
        confirmValueChange = { value ->
            if (value == DismissValue.DismissedToStart) {
                onDelete()
                true
            } else false
        }
    )

    SwipeToDismiss(
        state = dismissState,
        background = {
            val direction = dismissState.dismissDirection ?: return@SwipeToDismiss
            if (direction == DismissDirection.EndToStart) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(IOSRed)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.White
                    )
                }
            }
        },
        directions = setOf(DismissDirection.EndToStart),
        dismissContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(IOSCardBackground)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Match type indicator dot
                val isIntervention = intervention.matchType == "INTERVENTION"
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isIntervention) IOSRed else IOSGreen)
                )
                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            intervention.plannedAction.lowercase().replaceFirstChar { it.uppercase() },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = IOSLabel
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            intervention.matchType,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isIntervention) IOSRed else IOSGreen,
                            modifier = Modifier
                                .background(
                                    if (isIntervention) IOSRed.copy(alpha = 0.1f) else IOSGreen.copy(alpha = 0.1f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "${(intervention.matchConfidence * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = IOSSecondaryLabel
                        )
                    }
                    if (intervention.plannedDescription.isNotEmpty()) {
                        Text(
                            intervention.plannedDescription,
                            fontSize = 13.sp,
                            color = IOSSecondaryLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row {
                        Text(
                            dateFormat.format(java.util.Date(intervention.timestamp)),
                            fontSize = 11.sp,
                            color = IOSTertiaryLabel
                        )
                        if (intervention.currentApp.isNotEmpty()) {
                            Text(
                                " · ${intervention.currentApp}",
                                fontSize = 11.sp,
                                color = IOSTertiaryLabel
                            )
                        }
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceEventRow(
    event: com.agentrelay.intervention.AgentTraceEvent,
    dateFormat: java.text.SimpleDateFormat,
    onDelete: () -> Unit
) {
    val dismissState = rememberDismissState(
        confirmValueChange = { value ->
            if (value == DismissValue.DismissedToStart) {
                onDelete()
                true
            } else false
        }
    )

    SwipeToDismiss(
        state = dismissState,
        background = {
            val direction = dismissState.dismissDirection ?: return@SwipeToDismiss
            if (direction == DismissDirection.EndToStart) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(IOSRed)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                }
            }
        },
        directions = setOf(DismissDirection.EndToStart),
        dismissContent = {
            val (dotColor, icon) = when (event.eventType) {
                "TASK_START" -> IOSGreen to Icons.Default.PlayArrow
                "TASK_END" -> IOSGray to Icons.Default.Stop
                "PLANNING" -> Color(0xFF5856D6) to Icons.Default.Psychology
                "LLM_PLAN" -> IOSBlue to Icons.Default.AutoAwesome
                "STEP_EXECUTED" -> IOSGreen to Icons.Default.CheckCircle
                "STEP_FAILED" -> IOSRed to Icons.Default.Error
                "ERROR" -> IOSRed to Icons.Default.Warning
                else -> IOSGray to Icons.Default.Info
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(IOSCardBackground)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = dotColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            event.eventType.replace("_", " ").lowercase()
                                .replaceFirstChar { it.uppercase() },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = IOSLabel
                        )
                        if (event.action != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                event.action,
                                fontSize = 11.sp,
                                color = dotColor,
                                modifier = Modifier
                                    .background(dotColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        if (event.confidence != null) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                event.confidence,
                                fontSize = 10.sp,
                                color = IOSSecondaryLabel
                            )
                        }
                    }
                    if (event.description.isNotEmpty()) {
                        Text(
                            event.description,
                            fontSize = 13.sp,
                            color = IOSSecondaryLabel,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (event.failureReason != null) {
                        Text(
                            event.failureReason,
                            fontSize = 12.sp,
                            color = IOSRed,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row {
                        Text(
                            dateFormat.format(java.util.Date(event.timestamp)),
                            fontSize = 11.sp,
                            color = IOSTertiaryLabel
                        )
                        if (event.iteration > 0) {
                            Text(
                                " · iter ${event.iteration}",
                                fontSize = 11.sp,
                                color = IOSTertiaryLabel
                            )
                        }
                        if (event.currentApp != null) {
                            Text(
                                " · ${event.currentApp}",
                                fontSize = 11.sp,
                                color = IOSTertiaryLabel
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun IOSSectionHeader(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        color = IOSSecondaryLabel,
        modifier = Modifier.padding(start = 32.dp, bottom = 6.dp, top = 4.dp)
    )
}

@Composable
fun IOSGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = IOSCardBackground,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            content()
        }
    }
}

@Composable
fun IOSSettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color = IOSBlue,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = IOSLabel)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = IOSSecondaryLabel)
            }
        }
        trailing?.invoke()
    }
}
