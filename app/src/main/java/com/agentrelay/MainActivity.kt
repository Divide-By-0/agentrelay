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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
                    Text(
                        item.status,
                        fontSize = 15.sp,
                        color = IOSLabel,
                        maxLines = if (expanded) Int.MAX_VALUE else if (isReasoning) 3 else 1
                    )
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
                            secureStorage.saveApiKey(claudeKey) // legacy compat
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
            val fastModelOptions = listOf(
                "claude-haiku-4-5-20251001" to "Claude Haiku 4.5",
                "claude-sonnet-4-5" to "Claude Sonnet 4.5",
                "claude-opus-4-5" to "Claude Opus 4.5",
                "claude-opus-4-6" to "Claude Opus 4.6",
                "gpt-4o" to "GPT-4o",
                "gpt-4o-mini" to "GPT-4o Mini",
                "o4-mini" to "o4-mini",
                "gemini-2.0-flash-exp" to "Gemini 2.0 Flash",
                "gemini-2.0-flash-thinking-exp" to "Gemini 2.0 Flash Thinking",
                "gemini-exp-1206" to "Gemini Exp 1206"
            )

            fastModelOptions.forEachIndexed { index, (modelId, modelName) ->
                IOSSettingsRow(
                    title = modelName,
                    trailing = {
                        if (selectedModel == modelId) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = IOSBlue, modifier = Modifier.size(20.dp))
                        }
                    },
                    onClick = {
                        selectedModel = modelId
                        secureStorage.saveModel(modelId)
                    }
                )
                if (index < fastModelOptions.lastIndex) {
                    Divider(color = IOSSeparator, modifier = Modifier.padding(start = 16.dp))
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
            val thinkingModelOptions = listOf(
                "claude-opus-4-6" to "Claude Opus 4.6",
                "claude-sonnet-4-5" to "Claude Sonnet 4.5",
                "claude-opus-4-5" to "Claude Opus 4.5",
                "gpt-4o" to "GPT-4o",
                "o4-mini" to "o4-mini",
                "gemini-2.0-flash-thinking-exp" to "Gemini 2.0 Flash Thinking"
            )

            thinkingModelOptions.forEachIndexed { index, (modelId, modelName) ->
                IOSSettingsRow(
                    title = modelName,
                    trailing = {
                        if (selectedThinkingModel == modelId) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = IOSBlue, modifier = Modifier.size(20.dp))
                        }
                    },
                    onClick = {
                        selectedThinkingModel = modelId
                        secureStorage.savePlanningModel(modelId)
                    }
                )
                if (index < thinkingModelOptions.lastIndex) {
                    Divider(color = IOSSeparator, modifier = Modifier.padding(start = 16.dp))
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
                30 to "Low (fastest)"
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

        // ── Semantic Pipeline Section ──
        IOSSectionHeader("SEMANTIC PIPELINE")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var verificationEnabled by remember { mutableStateOf(secureStorage.getVerificationEnabled()) }
            var sendScreenshotsToLlm by remember { mutableStateOf(secureStorage.getSendScreenshotsToLlm()) }

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
                title = "Send Screenshots to LLMs",
                subtitle = if (sendScreenshotsToLlm) "Visual + structured context" else "Structured data only",
                trailing = {
                    Switch(
                        checked = sendScreenshotsToLlm,
                        onCheckedChange = {
                            sendScreenshotsToLlm = it
                            secureStorage.setSendScreenshotsToLlm(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IOSGreen
                        )
                    )
                }
            )
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
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
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
