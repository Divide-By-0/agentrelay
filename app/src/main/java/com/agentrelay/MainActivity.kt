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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    var apiKey by remember { mutableStateOf(secureStorage.getApiKey() ?: "") }
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
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 12.dp)
            )

            when (selectedTab) {
                0 -> AgentTab(
                    serviceRunning = serviceRunning,
                    accessibilityPermission = accessibilityPermission,
                    overlayPermission = overlayPermission,
                    isServiceOperationLoading = isServiceOperationLoading,
                    conversationItems = conversationItems,
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
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    onSaveApiKey = {
                        if (secureStorage.isValidApiKey(apiKey)) {
                            secureStorage.saveApiKey(apiKey)
                            Toast.makeText(context, "API key saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid API key format", Toast.LENGTH_SHORT).show()
                        }
                    },
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
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Status card at top
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (serviceRunning) IOSGreen.copy(alpha = 0.15f) else IOSGray6),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (serviceRunning) Icons.Default.PlayCircle else Icons.Default.PauseCircle,
                        contentDescription = null,
                        tint = if (serviceRunning) IOSGreen else IOSGray,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (serviceRunning) "Running" else "Stopped",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        color = IOSLabel
                    )
                    Text(
                        if (serviceRunning) "Tap 'Start Agent' in notification"
                        else if (!accessibilityPermission || !overlayPermission) "Grant permissions in Settings"
                        else "Ready to start",
                        fontSize = 13.sp,
                        color = IOSSecondaryLabel
                    )
                }
            }

            Divider(color = IOSSeparator, modifier = Modifier.padding(start = 74.dp))

            // Start/Stop button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isServiceOperationLoading) {
                        if (serviceRunning) onStopService() else onStartService()
                    }
                    .padding(16.dp),
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
                        fontWeight = FontWeight.Medium,
                        fontSize = 17.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Activity feed
        if (conversationItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Forum,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
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
            Text(
                "RECENT ACTIVITY",
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = IOSSecondaryLabel,
                modifier = Modifier.padding(start = 32.dp, bottom = 6.dp)
            )
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

@Composable
fun ActivityRow(item: ConversationItem) {
    var expanded by remember { mutableStateOf(false) }
    val iconColor = when (item.type) {
        ConversationItem.ItemType.ERROR -> IOSRed
        ConversationItem.ItemType.ACTION_EXECUTED -> IOSGreen
        ConversationItem.ItemType.SCREENSHOT_CAPTURED -> IOSOrange
        else -> IOSBlue
    }
    val icon = when (item.type) {
        ConversationItem.ItemType.SCREENSHOT_CAPTURED -> Icons.Default.PhotoCamera
        ConversationItem.ItemType.API_REQUEST -> Icons.Default.ArrowUpward
        ConversationItem.ItemType.API_RESPONSE -> Icons.Default.SmartToy
        ConversationItem.ItemType.ACTION_EXECUTED -> Icons.Default.CheckCircle
        ConversationItem.ItemType.ERROR -> Icons.Default.ErrorOutline
    }

    Surface(
        color = IOSCardBackground,
        shape = RoundedCornerShape(0.dp)
    ) {
        Column {
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
                    Text(
                        item.status,
                        fontSize = 15.sp,
                        color = IOSLabel,
                        maxLines = if (expanded) Int.MAX_VALUE else 1
                    )
                    if (item.actionDescription != null && !expanded) {
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

            if (expanded) {
                Column(modifier = Modifier.padding(start = 58.dp, end = 16.dp, bottom = 12.dp)) {
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

            Divider(color = IOSSeparator, modifier = Modifier.padding(start = 58.dp))
        }
    }
}

// ─── Settings Tab ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onSaveApiKey: () -> Unit,
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

        // ── API Key Section ──
        IOSSectionHeader("API KEY")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("sk-ant-api03-...", color = IOSTertiaryLabel) },
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
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onSaveApiKey,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = IOSBlue)
                ) {
                    Text("Save", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Model Section ──
        IOSSectionHeader("MODEL")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var selectedModel by remember { mutableStateOf(secureStorage.getModel()) }
            val modelOptions = listOf(
                "gemini-2.0-flash-exp" to "Gemini 2.0 Flash",
                "gemini-2.0-flash-thinking-exp" to "Gemini 2.0 Flash Thinking",
                "gemini-exp-1206" to "Gemini Exp 1206",
                "claude-opus-4-5" to "Claude Opus 4.5",
                "claude-sonnet-4-5" to "Claude Sonnet 4.5"
            )

            modelOptions.forEachIndexed { index, (modelId, modelName) ->
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
                if (index < modelOptions.lastIndex) {
                    Divider(color = IOSSeparator, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }

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

        // ── Semantic Pipeline Section ──
        IOSSectionHeader("SEMANTIC PIPELINE")
        IOSGroupedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            var verificationEnabled by remember { mutableStateOf(secureStorage.getVerificationEnabled()) }

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
        }

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
            "OCR enriches the element map for WebViews, games, and custom-rendered UIs.",
            fontSize = 13.sp,
            color = IOSSecondaryLabel,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            lineHeight = 18.sp
        )
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
