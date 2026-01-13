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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val screenCaptureRequestCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AgentRelayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
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
        // Check if service is already running
        if (ScreenCaptureService.instance != null) {
            return
        }

        // Check if we have API key
        val secureStorage = SecureStorage.getInstance(this)
        if (!secureStorage.hasApiKey()) {
            return
        }

        // Check if we have accessibility permission
        if (!AutomationService.isServiceEnabled()) {
            return
        }

        // Check if we have overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                return
            }
        }

        // All permissions granted, restart the service
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
            Toast.makeText(this, "Service started! Check the notification.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun AgentRelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFCC9B6D),
            secondary = Color(0xFFB89968),
            background = Color(0xFF1A1818),
            surface = Color(0xFF2B2826),
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color(0xFFE8E3E0),
            onSurface = Color(0xFFE8E3E0)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onRequestScreenCapture: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val secureStorage = remember { SecureStorage.getInstance(context) }

    var apiKey by remember { mutableStateOf(secureStorage.getApiKey() ?: "") }
    var serviceRunning by remember { mutableStateOf(ScreenCaptureService.instance != null) }
    var isServiceOperationLoading by remember { mutableStateOf(false) }
    var overlayPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        )
    }
    var accessibilityPermission by remember { mutableStateOf(AutomationService.isServiceEnabled()) }
    var statusMessages by remember { mutableStateOf(listOf<String>()) }
    var conversationItems by remember { mutableStateOf(listOf<ConversationItem>()) }
    var selectedTab by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    // Auto-refresh permissions every 10 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(10000)
            overlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
            accessibilityPermission = AutomationService.isServiceEnabled()
            serviceRunning = ScreenCaptureService.instance != null
        }
    }

    // Listen to status updates
    DisposableEffect(Unit) {
        val statusListener: (String) -> Unit = { message ->
            statusMessages = (statusMessages + message).takeLast(100)
        }
        val conversationListener: (List<ConversationItem>) -> Unit = { items ->
            conversationItems = items
        }
        StatusBroadcaster.addListener(statusListener)
        ConversationHistoryManager.addListener(conversationListener)
        onDispose {
            StatusBroadcaster.removeListener(statusListener)
            ConversationHistoryManager.removeListener(conversationListener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Bar
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Agent Relay",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Setup") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Activity") }
            )
        }

        when (selectedTab) {
            0 -> SetupTab(
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
                serviceRunning = serviceRunning,
                onRequestOverlayPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                },
                onRequestAccessibilityPermission = {
                    try {
                        // Try to open directly to our service's settings
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Add fragment arguments to try to highlight our service
                        val bundle = android.os.Bundle()
                        val componentName = "${context.packageName}/.AutomationService"
                        bundle.putString(":settings:fragment_args_key", componentName)
                        intent.putExtra(":settings:fragment_args_key", componentName)
                        intent.putExtra(":settings:show_fragment_args", bundle)
                        context.startActivity(intent)

                        // Show helpful toast
                        Toast.makeText(
                            context,
                            "Look for 'Agent Relay' in the list and toggle it on",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        // Fallback to general settings
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                },
                onStartService = {
                    if (!secureStorage.hasApiKey()) {
                        Toast.makeText(context, "Please set API key first", Toast.LENGTH_SHORT).show()
                    } else if (!accessibilityPermission) {
                        Toast.makeText(context, "Please enable Accessibility Service", Toast.LENGTH_SHORT).show()
                    } else if (!overlayPermission) {
                        Toast.makeText(context, "Please grant overlay permission", Toast.LENGTH_SHORT).show()
                    } else {
                        isServiceOperationLoading = true
                        onRequestScreenCapture()
                        // Reset loading state after a short delay since permission dialog opens
                        coroutineScope.launch {
                            delay(500)
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
                        delay(300) // Brief delay to ensure service stops
                        serviceRunning = false
                        isServiceOperationLoading = false
                    }
                },
                isServiceOperationLoading = isServiceOperationLoading
            )
            1 -> ActivityTab(conversationItems = conversationItems)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupTab(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    overlayPermission: Boolean,
    accessibilityPermission: Boolean,
    serviceRunning: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    isServiceOperationLoading: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // API Key Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Claude API Key",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("sk-ant-api03-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                // Model selection
                var modelExpanded by remember { mutableStateOf(false) }
                val secureStorage = SecureStorage.getInstance(LocalContext.current)
                var selectedModel by remember { mutableStateOf(secureStorage.getModel()) }
                val modelOptions = mapOf(
                    "claude-opus-4-5-20251101" to "Claude Opus 4.5 (Best)",
                    "claude-sonnet-4-5-20250514" to "Claude Sonnet 4.5 (Faster)"
                )

                Text(
                    "Model",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it }
                ) {
                    OutlinedTextField(
                        value = modelOptions[selectedModel] ?: "Claude Opus 4.5 (Best)",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        modelOptions.forEach { (model, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    selectedModel = model
                                    secureStorage.saveModel(model)
                                    modelExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                // Screenshot quality
                var qualityExpanded by remember { mutableStateOf(false) }
                var selectedQuality by remember { mutableStateOf(secureStorage.getScreenshotQuality()) }
                val qualityOptions = mapOf(
                    -1 to "Auto (adapts to speed)",
                    30 to "Low (JPEG 30%, fastest)"
                )

                Text(
                    "Screenshot Quality",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = qualityExpanded,
                    onExpandedChange = { qualityExpanded = it }
                ) {
                    OutlinedTextField(
                        value = qualityOptions[selectedQuality] ?: "Auto (adapts to speed)",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = qualityExpanded) },
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = qualityExpanded,
                        onDismissRequest = { qualityExpanded = false }
                    ) {
                        qualityOptions.forEach { (quality, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    selectedQuality = quality
                                    secureStorage.saveScreenshotQuality(quality)
                                    qualityExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                Button(
                    onClick = onSaveApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save API Key")
                }
            }
        }

        // Permissions Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                PermissionCard(
                    title = "Overlay Permission",
                    description = "Required to show cursor and overlays",
                    isGranted = overlayPermission,
                    onClick = onRequestOverlayPermission
                )

                PermissionCard(
                    title = "Accessibility Service",
                    description = "Required to control device",
                    isGranted = accessibilityPermission,
                    onClick = onRequestAccessibilityPermission
                )
            }
        }

        // Service Control
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (serviceRunning)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (serviceRunning) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (serviceRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (serviceRunning) "Service Running" else "Service Stopped",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    if (serviceRunning)
                        "Tap 'Start Agent' in the notification to begin automation"
                    else
                        "Start the service to enable the agent",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // Screen capture tip
                if (!serviceRunning) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Screen Capture Tip",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "When starting, choose 'Entire screen' in the dialog. Android will remember your choice for future sessions.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                if (serviceRunning) {
                    Button(
                        onClick = onStopService,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD32F2F)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 24.dp),
                        enabled = !isServiceOperationLoading
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isServiceOperationLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isServiceOperationLoading) "Stopping..." else "Stop Service",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = onStartService,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 24.dp),
                        enabled = !isServiceOperationLoading
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isServiceOperationLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isServiceOperationLoading) "Starting..." else "Start Service",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    // Define instructions based on title
    val instructions = when {
        title.contains("Accessibility") -> listOf(
            "1. Tap this card to open Accessibility Settings",
            "2. Look for 'Agent Relay' or 'Installed apps' section",
            "3. Tap on 'Agent Relay'",
            "4. Toggle the switch to ON",
            "5. Confirm when prompted"
        )
        title.contains("Overlay") -> listOf(
            "1. Tap this card to open settings",
            "2. Find 'Agent Relay' in the list",
            "3. Toggle 'Allow display over other apps' to ON"
        )
        else -> null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted)
                Color(0xFF1B3A1B)
            else
                MaterialTheme.colorScheme.background
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Icon(
                    if (isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (isGranted) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }

            // Show instructions button if not granted
            if (!isGranted && instructions != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (expanded) "Hide instructions" else "Show instructions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .background(
                                color = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        instructions.forEach { instruction ->
                            Text(
                                instruction,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityTab(conversationItems: List<ConversationItem>) {
    var expandedItems by remember { mutableStateOf(setOf<Long>()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(conversationItems.reversed()) { item ->
            val isExpanded = expandedItems.contains(item.timestamp)
            val icon = when (item.type) {
                ConversationItem.ItemType.SCREENSHOT_CAPTURED -> Icons.Default.PhotoCamera
                ConversationItem.ItemType.API_REQUEST -> Icons.Default.Send
                ConversationItem.ItemType.API_RESPONSE -> Icons.Default.SmartToy
                ConversationItem.ItemType.ACTION_EXECUTED -> Icons.Default.CheckCircle
                ConversationItem.ItemType.ERROR -> Icons.Default.Error
            }
            val iconColor = when (item.type) {
                ConversationItem.ItemType.ERROR -> Color(0xFFF44336)
                ConversationItem.ItemType.ACTION_EXECUTED -> Color(0xFF4CAF50)
                else -> MaterialTheme.colorScheme.primary
            }

            Card(
                onClick = {
                    expandedItems = if (isExpanded) {
                        expandedItems - item.timestamp
                    } else {
                        expandedItems + item.timestamp
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                            if (item.actionDescription != null && !isExpanded) {
                                Text(
                                    item.actionDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Icon(
                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(12.dp))

                        item.actionDescription?.let {
                            Text(
                                "Action: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        item.response?.let {
                            Text(
                                "Response: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        item.prompt?.let {
                            Text(
                                "Prompt: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        item.screenshot?.let { screenshot ->
                            Text(
                                "Screenshot (${item.screenshotWidth}x${item.screenshotHeight})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Decode and display the screenshot
                            val imageBitmap = remember(screenshot) {
                                try {
                                    val imageBytes = Base64.decode(screenshot, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.asImageBitmap()
                                } catch (e: Exception) {
                                    null
                                }
                            }

                            if (imageBitmap != null) {
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = "Screenshot",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Text(
                                    "Failed to load screenshot",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        if (conversationItems.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No activity yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        "Start the agent to see detailed conversation history",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}
