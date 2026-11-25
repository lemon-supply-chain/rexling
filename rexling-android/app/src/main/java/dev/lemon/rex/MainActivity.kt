package dev.lemon.rex

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.chrisbanes.haze.HazeState

class MainActivity : ComponentActivity() {
    private val model: RexModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The stored in-app appearance decides the window colour before Compose draws,
        // so a light appearance does not flash the dark theme's background.
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(windowBackgroundColor()),
        )
        consumeAlert(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // The IME inset animates frame by frame. Its end is the only moment the terminal
        // grid is settled, so the deferred PTY resize is flushed then instead of waiting
        // out the rest of its debounce. On closing, the full grid can be restored ahead
        // of time so content rolls down smoothly instead of leaving a blank gap.
        ViewCompat.setWindowInsetsAnimationCallback(
            window.decorView,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat,
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        val imeVisible =
                            ViewCompat.getRootWindowInsets(window.decorView)
                                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                        if (!imeVisible) model.onImeClosing()
                    }
                    return super.onStart(animation, bounds)
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat = insets

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                    // One animation frame later the settled inset has reached layout.
                    window.decorView.postOnAnimation { model.onImeAnimationEnd() }
                }
            },
        )
        setContent {
            val dark =
                when (model.appearance) {
                    Appearance.SYSTEM -> isSystemInDarkTheme()
                    Appearance.DARK -> true
                    Appearance.LIGHT -> false
                }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            RexTheme(dark, model.theme(dark), frostedEffectsEnabled = model.frostedEffectsEnabled) {
                RexApp(model)
            }
        }
    }

    /** Window background for the stored appearance; matches Theme.Rex and values-night. */
    private fun windowBackgroundColor(): Int {
        val stored = runCatching {
            Appearance.valueOf(getSharedPreferences("rex", 0).getString("appearance", "SYSTEM")!!)
        }
            .getOrDefault(Appearance.SYSTEM)
        val dark =
            when (stored) {
                Appearance.DARK -> true
                Appearance.LIGHT -> false
                Appearance.SYSTEM ->
                    (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        return getColor(if (dark) R.color.window_canvas_dark else R.color.launch_canvas)
    }

    private fun consumeAlert(intent: Intent?) {
        AlertDestinations(BackgroundAlerts.preferences(this))
            .consume(intent?.data)
            ?.let(model::openAlert)
        intent?.data = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeAlert(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        model.windowFocusChanged(hasFocus)
    }

    override fun onStart() {
        super.onStart()
        BackgroundAlerts.foreground.value = true
        model.resume()
    }

    override fun onStop() {
        if (!isChangingConfigurations) BackgroundAlerts.foreground.value = false
        model.pause()
        super.onStop()
    }
}

@Composable
internal fun RexTheme(
    dark: Boolean,
    theme: AppTheme,
    motionEnabled: Boolean = android.animation.ValueAnimator.areAnimatorsEnabled(),
    frostedEffectsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = remember(dark, theme.ui) { rexColorScheme(dark, theme.ui) }
    MaterialTheme(colorScheme = scheme, typography = RexTokens.type, shapes = RexTokens.shapes) {
        CompositionLocalProvider(
            LocalTerminalPalette provides theme.terminal,
            LocalContentColor provides scheme.onSurface,
            LocalMotionEnabled provides motionEnabled,
            LocalFrostedEffectsEnabled provides frostedEffectsEnabled,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RexApp(model: RexModel) {
    val client = model.activeClient
    val noticeHaze = remember { HazeState() }
    val captureNotice = rememberBackdropCapture(PortForwards.linkNotice != null)
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalKeepScreenAwake provides model.keepScreenAwake,
            LocalKeyboardSuggestions provides model.keyboardSuggestions,
        ) {
            Box(Modifier.fillMaxSize().frostSource(noticeHaze, captureNotice)) {
                key(model.activeServerId) { RexWorkspace(model, client) }
            }
        }
        ForwardLinkUi(
            Modifier.align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(end = 12.dp, bottom = 116.dp),
            hazeState = noticeHaze,
        )
    }
    if (model.notificationNotice.isNotBlank())
        AlertDialog(
            onDismissRequest = { model.notificationNotice = "" },
            title = { Text(stringResource(R.string.label_notification)) },
            text = { Text(model.notificationNotice) },
            confirmButton = {
                TextButton(onClick = { model.notificationNotice = "" }) {
                    Text(stringResource(R.string.label_ok))
                }
            },
        )
}

private data class TerminalCloseTarget(
    val session: String,
    val id: String,
    val label: String,
    val tab: Boolean,
    val blocks: List<String>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RexWorkspace(model: RexModel, client: RexConnection) {
    val state by client.chromeState.collectAsState()
    val networkConfigError by NetworkSettings.configurationError.collectAsState()
    val connectionRequested = model.activeConnectionRequested
    val connectionPhase =
        if (connectionRequested && state.phase in listOf("disconnected", "paused")) {
            if (client.hasScreen) "reconnecting" else "connecting"
        } else state.phase
    val navigation = client.navigationRequest
    val displayedSession = navigation.session ?: state.selectedSession
    val currentSessionKey = sessionKey(model.activeServerId, displayedSession)
    val sessionLabel = state.sessions.firstOrNull { it.id == displayedSession }?.label
    val sessionStrip =
        SessionCatalog.strip(
            model.sessionTargets,
            SessionTarget(
                model.activeServer,
                SessionItem(
                    displayedSession,
                    sessionLabel
                        ?: if (state.connected) stringResource(R.string.label_no_session)
                        else phaseLabel(state.phase),
                ),
            ),
        )
    val displayedBlock = navigation.block ?: state.selectedBlock
    val changingSession = navigation.session != null && navigation.session != state.selectedSession
    val windows = if (changingSession) emptyList() else client.orderedWindows(state)
    val window =
        windows.firstOrNull { w -> w.blocks.any { it.id == displayedBlock } }
            ?: windows.firstOrNull { it.blocks.isNotEmpty() }
    val activeWindowId = window?.id.orEmpty()
    val ready = state.connected && !navigation.pending && !state.creating
    val keyTarget = client.keyTarget()
    val terminalHaze = remember { HazeState() }
    val workspaceHaze = remember { HazeState() }
    var readyBackdrop by remember { mutableStateOf(false) }
    var floatingBackdrop by remember { mutableStateOf(false) }
    var ownerBackdrop by remember { mutableStateOf(false) }
    var shortcutBackdrop by remember { mutableStateOf(false) }
    val palette = LocalTerminalPalette.current
    var endpoint by rememberSaveable { mutableStateOf(client.savedEndpoint) }
    var servers by remember { mutableStateOf(false) }
    var addServer by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf("") }
    var newSession by remember { mutableStateOf(false) }
    var topBarExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(model.compactTopBar) { if (!model.compactTopBar) topBarExpanded = false }
    var menu by remember { mutableStateOf(false) }
    // Settings drawers stack, so Cancel, swipe-down and Back return to the level above.
    val settings = remember { mutableStateListOf<SettingsRoute>() }
    fun openSettings(route: SettingsRoute) {
        SettingsNav.open(settings, route)
    }
    fun closeSettings() {
        SettingsNav.close(settings)
    }
    val view = LocalView.current
    val activityWindow = remember(view) { (view.context as? android.app.Activity)?.window }
    val insetsController =
        remember(activityWindow, view) {
            activityWindow?.let { WindowCompat.getInsetsController(it, view) }
        }
    LaunchedEffect(model.compactTopBar, topBarExpanded, settings.size) {
        val shouldHide = model.compactTopBar && !topBarExpanded && settings.isEmpty()
        insetsController?.let { controller ->
            if (shouldHide) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { insetsController?.show(WindowInsetsCompat.Type.statusBars()) }
    }
    var pendingShortcut by remember {
        mutableStateOf<Triple<ShortcutProfile, NeovimShortcut, String>?>(null)
    }
    var forwards by remember { mutableStateOf(false) }
    var closeTarget by remember { mutableStateOf<TerminalCloseTarget?>(null) }
    val fontSize = model.fontSize
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    val context = LocalContext.current
    var networkAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var networkDenied by remember { mutableStateOf<(() -> Unit)?>(null) }
    val localNetwork =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = networkAction
            val denied = networkDenied
            networkAction = null
            networkDenied = null
            if (granted) action?.invoke()
            else {
                denied?.invoke()
                model.images.notice = context.getString(R.string.notice_local_network_upload)
            }
        }
    DisposableEffect(Unit) {
        onDispose {
            networkDenied?.invoke()
            networkAction = null
            networkDenied = null
        }
    }
    fun withUploadNetwork(action: () -> Unit, onDenied: () -> Unit = {}) {
        if (
            Build.VERSION.SDK_INT >= 37 &&
                context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            if (networkAction != null) {
                onDenied()
                return
            }
            networkAction = action
            networkDenied = onDenied
            localNetwork.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else action()
    }
    val imageCommit: (android.net.Uri, () -> Unit) -> Boolean = { uri, release ->
        val target = model.imageTarget()
        if (target == null) model.attachImage(uri, target, release)
        else {
            withUploadNetwork({ model.attachImage(uri, target, release) }, release)
            true
        }
    }
    fun pasteClipboard(): Boolean = runCatching {
        pasteTerminalClipboard(context, { uri -> imageCommit(uri, {}) }, client::paste)
    }
        .getOrElse {
            model.images.notice = context.getString(R.string.notice_clipboard_image_failed)
            false
        }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val target = model.pendingImageTarget
            model.pendingImageTarget = null
            if (uri != null) withUploadNetwork({ model.attachImage(uri, target) })
        }
    fun chooseImage() {
        val target = model.imageTarget()
        if (target == null) {
            model.images.notice = context.getString(R.string.notice_take_control_first)
            return
        }
        model.pendingImageTarget = target
        photoPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
    // Rex accepts any file type, so the document picker allows everything.
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = model.pendingImageTarget
            model.pendingImageTarget = null
            if (uri != null) withUploadNetwork({ model.attachImage(uri, target) })
        }
    fun chooseFile() {
        val target = model.imageTarget()
        if (target == null) {
            model.images.notice = context.getString(R.string.notice_take_control_first)
            return
        }
        model.pendingImageTarget = target
        filePicker.launch(arrayOf("*/*"))
    }
    DisposableEffect(client) { onDispose { model.setVisibleActivityPanes(null) } }
    val keyboardDensity = LocalDensity.current
    val keyboardInsets = WindowInsets.ime
    val keyboardVisible by
        remember(keyboardInsets, keyboardDensity) {
            derivedStateOf { keyboardInsets.getBottom(keyboardDensity) > 0 }
        }
    // Follow where the keyboard is heading, not its animated inset: the inset stays above zero
    // until the hide animation ends, which left the toolbar showing after the keyboard was gone.
    @OptIn(ExperimentalLayoutApi::class) val keyboardShown = WindowInsets.isImeVisible
    val toolbarHidden = model.toolbarWithKeyboardOnly && !keyboardShown
    val tabWidth = currentTabWidth()
    val panelShown = state.connected || state.phase == "preview" || client.hasScreen
    val unfoldedFullScreen = isUnfoldedFullScreen()
    val hasHinge =
        remember(context) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
        }
    val allowFloatingOverlay = unfoldedFullScreen
    val tabLayout = model.tabLayout(tabWidth.first)
    // Floating tabs are a pure overlay: they take no layout space, so moving them never resizes the
    // terminal. The docked rail and bottom strip step aside while this is on.
    val floatingTabs = model.floatingTabs
    val extendUnderNav =
        panelShown && tabLayout.side && !floatingTabs && model.toolbarWithKeyboardOnly
    val navigationClearance =
        with(keyboardDensity) {
            val inset = WindowInsets.navigationBars.getBottom(this).toDp()
            when {
                !extendUnderNav || inset == 0.dp -> 0.dp
                inset < 40.dp -> 14.dp
                else -> inset + 3.dp
            }
        }
    // The auto-hidden toolbar's unclipped height (divider + key row until measured). Written during
    // its measurement and read by the terminal's, so it is a plain holder rather than snapshot
    // state.
    val toolbarHeight =
        remember(keyboardDensity) {
            intArrayOf(with(keyboardDensity) { 1.dp.roundToPx() + 45.dp.roundToPx() })
        }
    val blocks =
        window?.blocks
            ?: if (state.phase == "preview") listOf(BlockItem("preview", "Terminal"))
            else emptyList()
    val requested = window?.layout?.firstOrNull { it.id == displayedBlock }
    val visibleBlocks = window?.visiblePanes() ?: blocks
    val chosen =
        displayedBlock.takeUnless { it.isBlank() || requested?.minimized == true }
            ?: visibleBlocks
                .firstOrNull { it.id == state.selectedBlock }
                ?.id
                .orEmpty()
                .ifBlank { visibleBlocks.firstOrNull()?.id.orEmpty() }
    val tiles =
        if (model.multiPaneUnfolded && unfoldedFullScreen)
            multiPaneTiles(window, chosen, allowFloatingOverlay)
        else null
    val showAllFloating = model.minimizeFloatingPanes || (allowFloatingOverlay && tiles != null)
    val floating = window?.floatingPanes(minimizedOnly = !showAllFloating).orEmpty()
    val carouselBlocks = window?.carouselPanes(model.minimizeFloatingPanes, chosen) ?: blocks
    val activeFloating =
        window
            ?.layout
            ?.firstOrNull { it.id == chosen && it.floating && !it.minimized }
            ?.takeIf { pane -> floating.any { it.id == pane.id } }
    val flushSinglePane = tabLayout.side && !floatingTabs && tiles == null
    val terminalBottomMargin =
        when {
            flushSinglePane -> 0.dp
            extendUnderNav -> navigationClearance
            else -> 3.dp
        }
    val singlePaneNavigationClearance = if (flushSinglePane) navigationClearance else 0.dp
    val bottomOverlayInset = if (keyboardShown) 0.dp else singlePaneNavigationClearance
    val floatingOverlay =
        if (allowFloatingOverlay && tiles != null) window?.visibleFloatingPlacements().orEmpty()
        else emptyList()
    var dockFloatingPane by remember(displayedSession, window?.id) { mutableStateOf<String?>(null) }
    val controlled = state.controlled && ready && client.hasScreen
    val paneObscured =
        menu ||
            model.activityVisible ||
            picker.isNotEmpty() ||
            settings.isNotEmpty() ||
            servers ||
            addServer ||
            forwards ||
            newSession ||
            pendingShortcut != null ||
            closeTarget != null ||
            dockFloatingPane != null
    var fannedTabId by remember { mutableStateOf<String?>(null) }
    var fanAnchorBounds by remember { mutableStateOf(Rect.Zero) }
    var fanBarBounds by remember { mutableStateOf(Rect.Zero) }
    fun resolveFannedWindow() =
        if (floatingTabs) windows.firstOrNull { it.id == fannedTabId && it.blocks.size > 1 }
        else null
    LaunchedEffect(
        floatingTabs,
        displayedSession,
        activeWindowId,
        tabLayout.position,
        paneObscured,
    ) {
        fannedTabId = null
        if (!floatingTabs) {
            fanAnchorBounds = Rect.Zero
            fanBarBounds = Rect.Zero
        }
    }
    LaunchedEffect(fannedTabId, windows, floatingTabs) {
        if (fannedTabId != null && resolveFannedWindow() == null) fannedTabId = null
    }
    val zoomed = window?.zoomed.orEmpty()
    val activityPaneIds =
        remember(chosen, tiles, floatingOverlay, zoomed) {
            activityPaneCandidates(chosen, tiles, floatingOverlay, zoomed)
        }
    val display = client.displayLayout.collectAsState()
    val visibleActivityBlocks by
        remember(client, display, activityPaneIds, ready, paneObscured, state.selectedBlock) {
            derivedStateOf<Set<String>>(structuralEqualityPolicy()) {
                if (!ready || paneObscured) emptySet()
                else
                    activityPaneIds.filterTo(mutableSetOf()) { id ->
                        id == state.selectedBlock && display.value.hasScreen ||
                            id in client.paneFrames
                    }
            }
        }
    val activityServer = model.activeServer
    val activityBadges =
        remember(
            model.activitySources,
            activityServer,
            displayedSession,
            windows,
            visibleActivityBlocks,
        ) {
            ActivityBadgePolicy.route(
                model.activitySources,
                activityServer,
                displayedSession,
                windows,
                visibleActivityBlocks,
            )
        }
    SideEffect {
        model.setVisibleActivityPanes(
            visibleActivityBlocks
                .takeIf { it.isNotEmpty() }
                ?.let {
                    WatchedPanes(
                        activityServer.id,
                        activityServer.endpoint,
                        displayedSession,
                        state.selectedBlock,
                        it,
                    )
                },
        )
    }
    fun openPane(id: String) {
        client.selectBlock(id, null, keepTiled = tiles != null, restoreMinimized = true)
    }
    fun minimizeFloating(id: String) {
        val tab = window ?: return
        val pane = tab.layout.firstOrNull { it.id == id && it.floating && !it.minimized } ?: return
        client.minimizeFloating(displayedSession, tab.id, pane)
    }
    LaunchedEffect(model.minimizeFloatingPanes) {
        if (model.minimizeFloatingPanes && activeFloating != null && tiles == null)
            client.floatingNavigation.returnPane(displayedSession, window)?.let(client::selectBlock)
    }
    SideEffect { window?.let { client.floatingNavigation.remember(displayedSession, it, chosen) } }
    BackHandler(
        enabled = model.compactTopBar && topBarExpanded && settings.isEmpty() && picker.isEmpty(),
    ) {
        topBarExpanded = false
    }
    BackHandler(
        enabled =
            ready &&
                !newSession &&
                settings.isEmpty() &&
                pendingShortcut == null &&
                !forwards &&
                !addServer &&
                closeTarget == null &&
                activeFloating?.layerId?.isNotBlank() == true &&
                picker.isEmpty() &&
                !model.activityVisible,
    ) {
        activeFloating?.let { minimizeFloating(it.id) }
    }
    val liveBlock = state.selectedBlock.ifBlank { if (state.phase == "preview") "preview" else "" }
    fun closePaneTarget(block: BlockItem) =
        TerminalCloseTarget(
            state.selectedSession,
            block.id,
            (state.programs[block.id] ?: ProgramIdentity.Loading).paneTitle(
                block.label,
                state.titles[block.id].orEmpty(),
            ),
            false,
            listOf(block.id),
        )
    fun closeTabTarget(tab: WindowItem): TerminalCloseTarget {
        val pane = client.tabPaneIndex(tab)
        val block = tab.blocks.getOrNull(pane)
        val program = state.programs[block?.id] ?: ProgramIdentity.Loading
        return TerminalCloseTarget(
            state.selectedSession,
            tab.id,
            program.tabTitle(
                tab.label,
                block?.label.orEmpty(),
                1,
                state.titles[block?.id].orEmpty(),
            ),
            true,
            tab.blocks.map { it.id },
        )
    }
    LaunchedEffect(state.selectedSession) { closeTarget = null }
    LaunchedEffect(palette) { model.updateTerminalTheme(palette) }
    LaunchedEffect(
        state.connected,
        state.selectedSession,
        state.selectedBlock,
        state.windows,
        navigation.pending,
    ) {
        client.openPreferredTerminal()
        client.rememberCurrentTerminal()
    }
    LaunchedEffect(
        state.connected,
        state.selectedSession,
        window?.id,
        state.selectedBlock,
        carouselBlocks,
        tiles != null,
    ) {
        if (tiles != null) return@LaunchedEffect
        val blocks = carouselBlocks
        val index = blocks.indexOfFirst { it.id == state.selectedBlock }
        if (index >= 0 && blocks.size > 1)
            client.prefetchPanes(
                listOf(
                        blocks[Math.floorMod(index - 1, blocks.size)].id,
                        blocks[(index + 1) % blocks.size].id,
                    )
                    .distinct(),
            )
    }
    LaunchedEffect(ready, chosen, client.hasScreen) { if (ready) client.claimActivePane() }
    LaunchedEffect(controlled, terminalView) {
        if (controlled) {
            terminalView?.requestFocus()
            client.applySize()
        }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(
        controlled,
        client.keyboardRequest,
        terminalView,
        displayedBlock,
        menu,
        windowFocused,
    ) {
        val view = terminalView
        if (
            windowFocused &&
                !menu &&
                controlled &&
                view != null &&
                client.keyboardRequest.isNotEmpty() &&
                client.keyboardRequest == displayedBlock
        ) {
            view.keyboard()
            client.keyboardShown()
        }
    }
    val chosenBlock = blocks.firstOrNull { it.id == chosen }
    val paneTitle =
        if (model.servers.isEmpty()) stringResource(R.string.app_name)
        else
            (state.programs[chosen] ?: ProgramIdentity.Loading).paneTitle(
                chosenBlock?.label.orEmpty(),
                state.titles[chosen].orEmpty(),
            )
    val workspaceBackdrop =
        rememberBackdropCapture(
            menu ||
                forwards ||
                settings.isNotEmpty() ||
                servers ||
                addServer ||
                picker in setOf("tabs", "panes", "sessions") ||
                model.activityVisible ||
                dockFloatingPane != null,
        )
    if (workspaceBackdrop && tiles != null)
        DisposableEffect(client) {
            client.setMirrorObscured(true)
            onDispose { client.setMirrorObscured(false) }
        }
    Surface(
        Modifier.fillMaxSize().frostSource(workspaceHaze, workspaceBackdrop),
        color = MaterialTheme.colorScheme.background,
    ) {
        val contentInsets =
            if (model.compactTopBar && !topBarExpanded)
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            else WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(contentInsets)
                .imePadding()
                .then(
                    if (panelShown) Modifier
                    else
                        Modifier.windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                        ),
                ),
        ) {
            if (model.compactTopBar) {
                AnimatedContent(
                    targetState = topBarExpanded,
                    label = "top_bar_expand",
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180)) +
                                expandVertically(
                                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
                                ))
                            .togetherWith(
                                fadeOut(animationSpec = tween(120)) +
                                    shrinkVertically(
                                        animationSpec =
                                            spring(dampingRatio = 0.85f, stiffness = 400f),
                                    ),
                            )
                    },
                ) { expanded ->
                    if (expanded) {
                        SessionHeader(
                            sessionStrip,
                            currentSessionKey,
                            state.connected,
                            model.sessionTargets.isNotEmpty(),
                            blocks.map { state.programs[it.id] ?: ProgramIdentity.Loading },
                            blocks.indexOfFirst { it.id == chosen }.coerceAtLeast(0),
                            state.directories[displayedBlock].orEmpty(),
                            { picker = "sessions" },
                            model::selectSessionTarget,
                            model::jumpLastSession,
                            serverIcons =
                                model.sessionTargets.associate { it.key to it.server.icon } +
                                    (currentSessionKey to model.activeServer.icon),
                            working = chosen in state.workingBlocks,
                            isPaneWorking = { index ->
                                blocks.getOrNull(index)?.id in state.workingBlocks
                            },
                            progress = state.terminalProgress[chosen],
                            title = paneTitle,
                            onCollapse = { topBarExpanded = false },
                            menu = {
                                ChromeButton(
                                    ChromeIcon.MORE,
                                    stringResource(R.string.terminal_menu),
                                ) {
                                    menu = true
                                }
                            },
                        )
                    } else {
                        CompactSessionHeader(
                            title = paneTitle,
                            directory = state.directories[displayedBlock].orEmpty(),
                            programs =
                                blocks.map { state.programs[it.id] ?: ProgramIdentity.Loading },
                            paneIndex = blocks.indexOfFirst { it.id == chosen }.coerceAtLeast(0),
                            working = chosen in state.workingBlocks,
                            isPaneWorking = { index ->
                                blocks.getOrNull(index)?.id in state.workingBlocks
                            },
                            onExpand = { topBarExpanded = true },
                        )
                    }
                }
            } else {
                SessionHeader(
                    sessionStrip,
                    currentSessionKey,
                    state.connected,
                    model.sessionTargets.isNotEmpty(),
                    blocks.map { state.programs[it.id] ?: ProgramIdentity.Loading },
                    blocks.indexOfFirst { it.id == chosen }.coerceAtLeast(0),
                    state.directories[displayedBlock].orEmpty(),
                    { picker = "sessions" },
                    model::selectSessionTarget,
                    model::jumpLastSession,
                    serverIcons =
                        model.sessionTargets.associate { it.key to it.server.icon } +
                            (currentSessionKey to model.activeServer.icon),
                    working = chosen in state.workingBlocks,
                    isPaneWorking = { index -> blocks.getOrNull(index)?.id in state.workingBlocks },
                    progress = state.terminalProgress[chosen],
                    title = paneTitle,
                    menu = {
                        ChromeButton(ChromeIcon.MORE, stringResource(R.string.terminal_menu)) {
                            menu = true
                        }
                    },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    if (
                        model.servers.isEmpty() ||
                            (!connectionRequested &&
                                state.phase == "disconnected" &&
                                !client.hasScreen)
                    ) {
                        Surface(
                            Modifier.padding(12.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            if (model.servers.isEmpty())
                                EmptyServerContent(onAdd = { addServer = true })
                            else
                                Column(
                                    Modifier.padding(15.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.app_name),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp,
                                    )
                                    Text(
                                        stringResource(R.string.app_subtitle),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(R.string.connect_over_tailscale),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    OutlinedTextField(
                                        endpoint,
                                        { endpoint = it },
                                        Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.rex_server_url)) },
                                        singleLine = true,
                                        keyboardOptions =
                                            KeyboardOptions(keyboardType = KeyboardType.Uri),
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    connectionError?.let {
                                        Text(
                                            it,
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { connectionError = model.connect(endpoint) },
                                            enabled = endpoint.isNotBlank(),
                                        ) {
                                            Text(stringResource(R.string.connect))
                                        }
                                        TextButton(onClick = { servers = true }) {
                                            Text(stringResource(R.string.servers))
                                        }
                                        if (state.connected || state.phase == "reconnecting")
                                            TextButton(
                                                onClick = {
                                                    model.disconnectServer(model.activeServerId)
                                                },
                                            ) {
                                                Text(stringResource(R.string.disconnect))
                                            }
                                        else
                                            TextButton(
                                                onClick = {
                                                    model.previewServer(model.activeServerId)
                                                },
                                            ) {
                                                Text(stringResource(R.string.try_preview))
                                            }
                                    }
                                }
                        }
                    }
                    if (blocks.isEmpty() && model.servers.isNotEmpty())
                        ConnectionNotice(
                            connectionPhase,
                            model.networkAvailable,
                            client::retryNow,
                            configError = networkConfigError,
                        )
                    if (
                        blocks.isEmpty() &&
                            state.connected &&
                            !state.creating &&
                            state.sessions.isEmpty()
                    ) {
                        EmptySessionContent(
                            model.activeServer.label,
                            ready,
                            { newSession = true },
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    if (
                        model.servers.isNotEmpty() &&
                            state.error.isNotBlank() &&
                            state.phase !in listOf("connecting", "reconnecting")
                    )
                        Text(
                            state.error,
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(10.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 11.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    val tabActions: @Composable (Boolean, Boolean) -> Unit = { vertical, sideRail ->
                        TerminalActions(
                            state.connected && state.selectedSession.isNotBlank(),
                            model.connectedCount > 0,
                            { model.showActivity(true) },
                            { picker = "tabs" },
                            attentionCount = activityBadges.elsewhere,
                            keyboardLabel =
                                if (!controlled)
                                    stringResource(R.string.a11y_take_control_and_show_keyboard)
                                else stringResource(R.string.a11y_show_keyboard),
                            onKeyboard =
                                if (toolbarHidden)
                                    ({
                                        if (!controlled) client.takeControl()
                                        else terminalView?.keyboard()
                                    })
                                else null,
                            vertical = vertical,
                            sideRail = sideRail,
                        )
                    }
                    val tabRail: @Composable ((@Composable () -> Unit)?) -> Unit = { handle ->
                        val isFloatingRail = floatingTabs && handle != null
                        TerminalTabRail(
                            windows,
                            activeWindowId,
                            state.connected && !state.creating && !changingSession,
                            client::selectWindow,
                            client::tabPrograms,
                            client::tabPaneIndex,
                            tabLayout.position,
                            tabLayout.style,
                            paneTitle = { w, pane ->
                                state.titles[w.blocks.getOrNull(pane)?.id].orEmpty()
                            },
                            paneWorking = { w -> w.blocks.any { it.id in state.workingBlocks } },
                            onClose = { closeTarget = closeTabTarget(it) },
                            onMove = client::moveTab,
                            reorderKey = displayedSession,
                            paneProgress = { w ->
                                state.terminalProgress[
                                        w.blocks.getOrNull(client.tabPaneIndex(w))?.id]
                                    ?: w.blocks
                                        .mapNotNull { state.terminalProgress[it.id] }
                                        .firstOrNull()
                            },
                            tabAttention = { w -> activityBadges.tabs[w.id] ?: 0 },
                            animateWorking = !workspaceBackdrop,
                            onStyle = {
                                model.changeTabLayout(tabWidth.first, tabLayout.copy(style = it))
                            },
                            extendUnderNav = extendUnderNav,
                            island = tiles != null && !floatingTabs,
                            islandBottom =
                                terminalBottomMargin + (if (extendUnderNav) 0.dp else 8.dp),
                            floating = floatingTabs,
                            hazeState = terminalHaze,
                            handle = handle,
                            fannedWindowId =
                                if (isFloatingRail) resolveFannedWindow()?.id else null,
                            onFanToggle =
                                if (isFloatingRail)
                                    { id -> fannedTabId = if (fannedTabId == id) null else id }
                                else null,
                            onFanDismiss = { fannedTabId = null },
                            onFanAnchorBounds = { if (isFloatingRail) fanAnchorBounds = it },
                            onFanBarBounds = { if (isFloatingRail) fanBarBounds = it },
                            onFanAnchorGone = { id -> if (fannedTabId == id) fannedTabId = null },
                        ) {
                            tabActions(tabLayout.style == TabRailStyle.ICONS, true)
                        }
                    }
                    // A floating strip always carries a grip; the docked strip never does.
                    val tabStrip: @Composable ((@Composable () -> Unit)?) -> Unit = { handle ->
                        val isFloatingStrip = floatingTabs && handle != null
                        TerminalTabs(
                            windows,
                            activeWindowId,
                            state.connected && !state.creating && !changingSession,
                            client::selectWindow,
                            panePrograms = client::tabPrograms,
                            selectedPane = client::tabPaneIndex,
                            paneTitle = { window, pane ->
                                state.titles[window.blocks.getOrNull(pane)?.id].orEmpty()
                            },
                            isPaneWorking = { window, pane ->
                                window.blocks.getOrNull(pane)?.id in state.workingBlocks
                            },
                            paneWorking = { window ->
                                window.blocks.any { it.id in state.workingBlocks }
                            },
                            onClose = { closeTarget = closeTabTarget(it) },
                            onMove = client::moveTab,
                            reorderKey = displayedSession,
                            animateWorking = !workspaceBackdrop,
                            tabAttention = { window -> activityBadges.tabs[window.id] ?: 0 },
                            paneProgress = { window ->
                                state.terminalProgress[
                                        window.blocks.getOrNull(client.tabPaneIndex(window))?.id]
                                    ?: window.blocks.firstNotNullOfOrNull {
                                        state.terminalProgress[it.id]
                                    }
                            },
                            contentReady =
                                client.hasScreen &&
                                    !state.creating &&
                                    !changingSession &&
                                    windows.isNotEmpty() &&
                                    windows.all { window ->
                                        val block =
                                            window.blocks.getOrNull(client.tabPaneIndex(window))
                                        block != null && state.programs[block.id]?.pending == false
                                    },
                            floating = handle != null,
                            hazeState = terminalHaze,
                            handle = handle,
                            fannedWindowId =
                                if (isFloatingStrip) resolveFannedWindow()?.id else null,
                            onFanToggle =
                                if (isFloatingStrip)
                                    { id -> fannedTabId = if (fannedTabId == id) null else id }
                                else null,
                            onFanDismiss = { fannedTabId = null },
                            onFanAnchorBounds = { if (isFloatingStrip) fanAnchorBounds = it },
                            onFanBarBounds = { if (isFloatingStrip) fanBarBounds = it },
                            onFanAnchorGone = { id -> if (fannedTabId == id) fannedTabId = null },
                            actions = { tabActions(false, false) },
                        )
                    }
                    if (blocks.isNotEmpty())
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            if (!floatingTabs && tabLayout.position == TabBarPosition.LEFT)
                                tabRail(null)
                            Box(
                                Modifier.weight(1f)
                                    .fillMaxHeight()
                                    .padding(bottom = terminalBottomMargin),
                            ) {
                                val onView: (TerminalView) -> Unit = { view ->
                                    terminalView = view
                                    view.onOpenLink = { url ->
                                        val loopback = LoopbackLink.parse(url)
                                        if (loopback == null) PortForwards.openBrowser(context, url)
                                        else if (
                                            ready &&
                                                chosen == liveBlock &&
                                                displayedSession.isNotBlank()
                                        ) {
                                            val sourceServer = model.activeServer
                                            val sourceSession = displayedSession
                                            withUploadNetwork(
                                                {
                                                    if (
                                                        model.servers.any {
                                                            it.id == sourceServer.id &&
                                                                it.endpoint == sourceServer.endpoint
                                                        }
                                                    )
                                                        PortForwards.openLink(
                                                            context,
                                                            sourceServer,
                                                            sourceSession,
                                                            loopback,
                                                        )
                                                },
                                            )
                                        } else
                                            PortForwards.notice =
                                                context.getString(
                                                    R.string.notice_wait_before_forward,
                                                )
                                    }
                                }
                                val captureTerminal =
                                    floatingBackdrop ||
                                        ownerBackdrop ||
                                        readyBackdrop ||
                                        shortcutBackdrop ||
                                        floatingTabs ||
                                        model.images.state != null
                                if (carouselBlocks.isEmpty())
                                    Box(
                                        Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            stringResource(R.string.floating_panes_minimized),
                                            style = SettingsType.detail,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                else if (tiles != null && window != null) {
                                    MultiPaneLayout(
                                        client,
                                        displayedSession,
                                        window.id,
                                        tiles,
                                        chosen,
                                        liveBlock,
                                        controlled,
                                        state.programs,
                                        state.mirrorWaiting,
                                        fontSize.toFloat(),
                                        onFocus = { id, grid ->
                                            client.selectBlock(id, grid, keepTiled = true)
                                        },
                                        onView = onView,
                                        onImage = imageCommit,
                                        onPaste = ::pasteClipboard,
                                        imeEnterSubmits = model.enterSubmits,
                                        keyboardChrome = {
                                            if (model.toolbarWithKeyboardOnly) toolbarHeight[0]
                                            else 0
                                        },
                                        extendsUnderNavigation = extendUnderNav,
                                        hazeState = terminalHaze,
                                        captureBackdrop = captureTerminal,
                                        bottomPaddingDp = if (floating.isNotEmpty()) 28 else 6,
                                        bottomMargin = !extendUnderNav,
                                        sideRail =
                                            if (floatingTabs) TabBarPosition.BOTTOM
                                            else tabLayout.position,
                                        splits = window.splits,
                                        zoomed = window.zoomed,
                                        paneActionsEnabled = ready,
                                        floatingPanes = floatingOverlay,
                                        allowFloat = allowFloatingOverlay && tiles.size > 1,
                                        onMinimizeFloating = ::minimizeFloating,
                                        onDockFloating = { dockFloatingPane = it },
                                        onClosePane = { id ->
                                            blocks
                                                .firstOrNull { it.id == id }
                                                ?.let { closeTarget = closePaneTarget(it) }
                                        },
                                    )
                                } else {
                                    LiveTerminal(
                                        client,
                                        carouselBlocks,
                                        chosen,
                                        liveBlock,
                                        state.programs,
                                        controlled,
                                        fontSize.toFloat(),
                                        client::type,
                                        client::scroll,
                                        client::wheel,
                                        client::takeControl,
                                        client::scrollToBottom,
                                        client::resize,
                                        ::openPane,
                                        { picker = "panes" },
                                        onView,
                                        Modifier.fillMaxSize(),
                                        floatingDock = floating.isNotEmpty(),
                                        paneLayout = window?.layout.orEmpty(),
                                        titles = state.titles,
                                        directories = state.directories,
                                        hazeState = terminalHaze,
                                        captureBackdrop = captureTerminal,
                                        onImage = imageCommit,
                                        onPaste = ::pasteClipboard,
                                        padding = model.terminalPadding,
                                        extendPadding = model.extendPadding,
                                        imeEnterSubmits = model.enterSubmits,
                                        keyboardChrome = {
                                            if (model.toolbarWithKeyboardOnly) toolbarHeight[0]
                                            else 0
                                        },
                                        squareCorners = tabLayout.side && !floatingTabs,
                                        extendsUnderNavigation = extendUnderNav,
                                        navigationClearance = singlePaneNavigationClearance,
                                        bottomOverlayInset = bottomOverlayInset,
                                    )
                                }
                                ConnectionNotice(
                                    connectionPhase,
                                    model.networkAvailable,
                                    client::retryNow,
                                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                                    configError = networkConfigError,
                                )
                                ReadyNotice(
                                    state.connectionEpoch,
                                    controlled,
                                    ready,
                                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                                    hazeState = terminalHaze,
                                    onVisibleChange = { readyBackdrop = it },
                                )
                                OtherOwnerNotice(
                                    chosen,
                                    state.otherOwner && !controlled,
                                    ready,
                                    client.screenDimensions,
                                    { client.takeControl() },
                                    Modifier.align(Alignment.TopEnd).padding(10.dp),
                                    hazeState = terminalHaze,
                                    onVisibleChange = { ownerBackdrop = it },
                                )
                                val shortcutProfile =
                                    if (
                                        ready &&
                                            chosen == liveBlock &&
                                            chosen !in state.cachedPrograms
                                    )
                                        ShortcutProfiles.active(
                                            model.shortcutProfiles,
                                            state.programs[chosen],
                                        )
                                    else null
                                if (shortcutProfile != null) {
                                    NeovimShortcutButton(
                                        shortcutProfile.items,
                                        keyTarget,
                                        controlled,
                                        onChoose = { item, target ->
                                            if (item.confirm)
                                                pendingShortcut =
                                                    Triple(shortcutProfile, item, target)
                                            else
                                                client.shortcut(
                                                    model.shortcutProfiles,
                                                    shortcutProfile,
                                                    item,
                                                    target,
                                                )
                                        },
                                        onCustomize = {
                                            openSettings(
                                                SettingsRoute.ShortcutEditor(shortcutProfile.id),
                                            )
                                        },
                                        layout = model.neovimShortcutLayout,
                                        effect = model.neovimShortcutEffect,
                                        hapticsEnabled = model.neovimShortcutHaptics,
                                        name = shortcutProfile.name,
                                        modifier = Modifier.fillMaxSize(),
                                        position = model.neovimShortcutPosition,
                                        onPosition = model::moveNeovimShortcut,
                                        bottomInset =
                                            bottomOverlayInset +
                                                (if (
                                                    floating.isNotEmpty() || carouselBlocks.size > 1
                                                )
                                                    64.dp
                                                else 10.dp),
                                        hazeState = terminalHaze,
                                        onExpandedChange = { shortcutBackdrop = it },
                                    )
                                }
                                if (floating.isNotEmpty()) {
                                    FloatingPaneDock(
                                        floating,
                                        activeFloating?.id,
                                        state.programs,
                                        state.titles,
                                        ready,
                                        ::openPane,
                                        { activeFloating?.let { minimizeFloating(it.id) } },
                                        Modifier.align(Alignment.BottomEnd)
                                            .padding(end = 6.dp, bottom = bottomOverlayInset),
                                        directories = state.directories,
                                        hazeState = terminalHaze,
                                        onExpandedChange = { floatingBackdrop = it },
                                    )
                                }
                                if (floatingTabs) {
                                    val fannedWindow = resolveFannedWindow()
                                    val fanSourceWindow =
                                        fannedWindow ?: window?.takeIf { it.blocks.size > 1 }
                                    val selectedFanBlockId =
                                        fanSourceWindow
                                            ?.blocks
                                            ?.getOrNull(client.tabPaneIndex(fanSourceWindow))
                                            ?.id ?: chosen
                                    val fanItems =
                                        remember(
                                            fanSourceWindow,
                                            selectedFanBlockId,
                                            state.programs,
                                            state.titles,
                                            state.workingBlocks,
                                            state.terminalProgress,
                                        ) {
                                            fanSourceWindow
                                                ?.blocks
                                                ?.map { block ->
                                                    val program =
                                                        state.programs[block.id]
                                                            ?: ProgramIdentity.Loading
                                                    TabFanItem(
                                                        id = block.id,
                                                        program = program,
                                                        title =
                                                            program.paneTitle(
                                                                block.label,
                                                                state.titles[block.id].orEmpty(),
                                                            ),
                                                        selected = block.id == selectedFanBlockId,
                                                        working = block.id in state.workingBlocks,
                                                        progress = state.terminalProgress[block.id],
                                                    )
                                                }
                                                .orEmpty()
                                        }
                                    val fanMarquee =
                                        rememberRainbowMarquee(
                                            !workspaceBackdrop &&
                                                fanItems.any {
                                                    it.working || it.progress?.busy == true
                                                },
                                        )
                                    TabStackFan(
                                        visible = fannedWindow != null && !paneObscured,
                                        items = fanItems,
                                        anchorBounds = fanAnchorBounds,
                                        barBounds = fanBarBounds,
                                        preferredAxis =
                                            if (tabLayout.side) Orientation.Vertical
                                            else Orientation.Horizontal,
                                        marquee = fanMarquee,
                                        onSelect = { blockId ->
                                            fannedTabId = null
                                            openPane(blockId)
                                        },
                                        onDismiss = { fannedTabId = null },
                                        bar = {
                                            FloatingTabBar(
                                                model.floatingTabPosition,
                                                {
                                                    fannedTabId = null
                                                    model.moveFloatingTab(it)
                                                },
                                                Modifier.fillMaxSize(),
                                                // Only the left and right rails tuck themselves
                                                // away.
                                                hideAtEdges = tabLayout.side,
                                                keepVisible = fannedWindow != null && !paneObscured,
                                                onDragStart = { fannedTabId = null },
                                            ) { handle ->
                                                if (tabLayout.position == TabBarPosition.BOTTOM)
                                                    Box(Modifier.width(FloatingTabBarWidth)) {
                                                        tabStrip(handle)
                                                    }
                                                else tabRail(handle)
                                            }
                                        },
                                    )
                                }
                            }
                            if (!floatingTabs && tabLayout.position == TabBarPosition.RIGHT)
                                tabRail(null)
                        }
                    else
                        Box(
                            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.connected || !connectionRequested) {
                                Text(
                                    if (state.connected)
                                        stringResource(R.string.choose_session_or_start)
                                    else stringResource(R.string.terminals_anywhere),
                                    fontFamily = MonoUi,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    if (state.connected || state.phase == "preview" || client.hasScreen) {
                        WorkspaceBottomBar(
                            model,
                            client,
                            state,
                            changingSession,
                            // Without a docked rail the bar keeps its rounded top.
                            tabLayout.side && !floatingTabs,
                            extendUnderNav,
                            controlled,
                            ready,
                            keyboardVisible,
                            keyTarget,
                            toolbarHeight,
                            onPaste = ::pasteClipboard,
                            onKeyboard = {
                                if (!controlled) client.takeControl()
                                else if (keyboardVisible) terminalView?.hideKeyboard()
                                else terminalView?.keyboard()
                            },
                            tabs =
                                if (floatingTabs) null
                                else {
                                    { tabStrip(null) }
                                },
                        )
                    }
                }
                model.images.state?.let { status ->
                    ImageUploadOverlay(
                        status,
                        model.images::retry,
                        model.images::cancel,
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(horizontal = 16.dp)
                                .padding(
                                    bottom =
                                        if (state.connected)
                                            if (floating.isNotEmpty()) 152.dp else 112.dp
                                        else 16.dp,
                                ),
                        retryable = model.images.retryable,
                        hazeState = terminalHaze,
                    )
                }
                // Loading feedback must not resize the terminal grid during navigation.
                if (state.creating || navigation.pending)
                    LinearProgressIndicator(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp),
                    )
            }
        }
    }
    if (menu) {
        val canTakeControl = ready && client.hasScreen
        val canAttach = controlled && model.images.state == null
        TerminalActionsDrawer(
            title =
                if (chosenBlock == null) stringResource(R.string.terminal_actions) else paneTitle,
            subtitle =
                listOfNotNull(
                        sessionLabel,
                        model.activeServer.label.takeIf { model.servers.isNotEmpty() },
                    )
                    .joinToString(" · "),
            status =
                if (state.connected)
                    if (controlled) stringResource(R.string.in_control)
                    else stringResource(R.string.viewing_terminal)
                else phaseLabel(state.phase),
            dimensions = client.screenDimensions,
            controlled = controlled,
            ownership =
                if (canTakeControl)
                    ({ if (controlled) client.releaseControl() else client.takeControl() })
                else null,
            quickActions =
                listOfNotNull(
                    TerminalDrawerAction(
                        stringResource(R.string.paste),
                        ChromeIcon.PASTE,
                        enabled = controlled,
                    ) {
                        pasteClipboard()
                    },
                    TerminalDrawerAction(
                        stringResource(R.string.image),
                        ChromeIcon.IMAGE,
                        enabled = canAttach,
                        run = ::chooseImage,
                    ),
                    TerminalDrawerAction(
                        stringResource(R.string.attach_file),
                        ChromeIcon.FILE,
                        enabled = canAttach,
                        run = ::chooseFile,
                    ),
                    TerminalDrawerAction(
                        stringResource(R.string.copy_viewport),
                        ChromeIcon.COPY,
                        enabled = client.hasScreen,
                    ) {
                        client.terminal.value?.let { screen ->
                            (getClipboard(context) as ClipboardManager).setPrimaryClip(
                                ClipData.newPlainText(
                                    context.getString(R.string.clip_rex_terminal),
                                    screen.plainText(),
                                ),
                            )
                        }
                    },
                    if (unfoldedFullScreen) {
                        val multiple = model.multiPaneUnfolded
                        TerminalDrawerAction(
                            if (multiple) stringResource(R.string.single_pane)
                            else stringResource(R.string.multiple_panes),
                            if (multiple) ChromeIcon.ZOOM else ChromeIcon.SPLIT,
                        ) {
                            model.changeMultiPaneUnfolded(!multiple)
                        }
                    } else null,
                    TerminalDrawerAction(
                        if (palette.dark) stringResource(R.string.light_theme)
                        else stringResource(R.string.dark_theme),
                        if (palette.dark) ChromeIcon.SUN else ChromeIcon.MOON,
                    ) {
                        model.changeAppearance(
                            if (palette.dark) Appearance.LIGHT else Appearance.DARK,
                        )
                    },
                ),
            actions =
                listOf(
                    TerminalDrawerAction(
                        stringResource(R.string.new_session),
                        ChromeIcon.ADD,
                        enabled = ready,
                        opensDrawer = true,
                    ) {
                        newSession = true
                    },
                    TerminalDrawerAction(
                        stringResource(R.string.port_forwarding),
                        ChromeIcon.PORT_FORWARD,
                        opensDrawer = true,
                    ) {
                        forwards = true
                    },
                    TerminalDrawerAction(
                        stringResource(R.string.servers_connected_format, model.connectedCount),
                        ChromeIcon.SERVER,
                        opensDrawer = true,
                    ) {
                        servers = true
                    },
                    TerminalDrawerAction(
                        stringResource(R.string.refresh_sessions),
                        ChromeIcon.REFRESH,
                        enabled = state.connected,
                    ) {
                        client.refresh()
                    },
                ),
            onPreferences = { openSettings(SettingsRoute.Preferences) },
            onClosePane =
                chosenBlock
                    ?.takeIf { ready }
                    ?.let { block -> { closeTarget = closePaneTarget(block) } },
            onDismiss = { menu = false },
            hazeState = workspaceHaze,
        )
    }
    if (model.activityVisible)
        ActivitySheet(
            model.activitySources,
            model::refreshActivity,
            model::openActivity,
            onClose = { model.showActivity(false) },
            onSeen = model::markActivitySeen,
            onMarkAll = model::markAllActivitySeen,
            hazeState = workspaceHaze,
        )
    NetworkPrompts()
    // Only the top of the stack renders; closing it reveals the level it was opened from.
    when (val route = settings.lastOrNull()) {
        null -> Unit
        SettingsRoute.Preferences ->
            PreferencesSheet(
                { openSettings(SettingsRoute.Appearance) },
                { openSettings(SettingsRoute.FontSize) },
                { openSettings(SettingsRoute.BackgroundAlerts) },
                { openSettings(SettingsRoute.About) },
                onClose = { closeSettings() },
                shortcutGesture = model.shortcutGesture,
                onShortcutGesture = model::changeShortcutGesture,
                onNeovim = { openSettings(SettingsRoute.Shortcuts) },
                arrowJoystickEnabled = model.arrowJoystickEnabled,
                onArrowJoystick = model::changeArrowJoystick,
                enterSubmits = model.enterSubmits,
                onEnterSubmits = model::changeEnterSubmits,
                toolbarWithKeyboardOnly = model.toolbarWithKeyboardOnly,
                onToolbarWithKeyboardOnly = model::changeToolbarWithKeyboardOnly,
                multiPaneUnfolded = model.multiPaneUnfolded,
                onMultiPaneUnfolded = model::changeMultiPaneUnfolded,
                tabLayouts = model.tabLayouts,
                tabWidth = tabWidth,
                onTabLayout = model::changeTabLayout,
                floatingTabs = model.floatingTabs,
                onFloatingTabs = model::changeFloatingTabs,
                compactTopBar = model.compactTopBar,
                onCompactTopBar = model::changeCompactTopBar,
                codexTitleAttention = model.experimentalCodexAttention,
                onCodexTitleAttention = model::changeCodexAttention,
                frostedEffectsEnabled = model.frostedEffectsEnabled,
                onFrostedEffects = model::changeFrostedEffects,
                minimizeFloatingPanes = model.minimizeFloatingPanes,
                onMinimizeFloatingPanes = model::changeMinimizeFloatingPanes,
                onNetwork = { openSettings(SettingsRoute.Network) },
                keepScreenAwake = model.keepScreenAwake,
                onKeepScreenAwake = model::changeKeepScreenAwake,
                onToolbar = { openSettings(SettingsRoute.KeyboardToolbar) },
                onControlItems = { openSettings(SettingsRoute.ControlShortcuts) },
                onSshKeys = { openSettings(SettingsRoute.SshKeys) },
                keyboardSuggestionsEnabled = model.keyboardSuggestions,
                onKeyboardSuggestions = model::changeKeyboardSuggestions,
                language = model.language,
                onLanguage = { openSettings(SettingsRoute.Language) },
                terminalPadding = model.terminalPadding,
                onTerminalPadding = model::changeTerminalPadding,
                extendPadding = model.extendPadding,
                onExtendPadding = model::changeExtendPadding,
                predictiveEcho = model.predictiveEcho,
                onPredictiveEcho = model::changePredictiveEcho,
                hazeState = workspaceHaze,
            )
        SettingsRoute.Appearance ->
            AppearanceSheet(
                model.appearance,
                model.themes,
                model.theme(false),
                model.theme(true),
                model::changeAppearance,
                model::chooseTheme,
                hazeState = workspaceHaze,
            ) {
                closeSettings()
            }
        SettingsRoute.FontSize ->
            FontSizeDialog(fontSize, model::changeFontSize, hazeState = workspaceHaze) {
                closeSettings()
            }
        SettingsRoute.Language ->
            LanguageSheet(model.language, model::changeLanguage, hazeState = workspaceHaze) {
                closeSettings()
            }
        SettingsRoute.BackgroundAlerts ->
            BackgroundAlertsDialog(model, hazeState = workspaceHaze) { closeSettings() }
        SettingsRoute.About -> AboutSheet(hazeState = workspaceHaze) { closeSettings() }
        SettingsRoute.KeyboardToolbar ->
            KeyboardToolbarSheet(
                model.toolbarKeys,
                model::changeToolbar,
                onClose = { closeSettings() },
                hazeState = workspaceHaze,
            )
        SettingsRoute.ControlShortcuts ->
            ControlShortcutsSheet(
                model.controlItems,
                model::saveControlShortcuts,
                onClose = { closeSettings() },
                hazeState = workspaceHaze,
            )
        SettingsRoute.SshKeys ->
            SshKeysSheet(onClose = { closeSettings() }, hazeState = workspaceHaze)
        SettingsRoute.Network ->
            NetworkSheet(model.activeServer, state.connected, hazeState = workspaceHaze) {
                closeSettings()
            }
        SettingsRoute.Shortcuts ->
            ShortcutProfilesSheet(
                model.shortcutProfiles,
                onEdit = { openSettings(SettingsRoute.ShortcutEditor(it)) },
                onNew = { openSettings(SettingsRoute.ShortcutEditor("")) },
                onEnabled = model::enableShortcutProfile,
                onClose = { closeSettings() },
                layout = model.neovimShortcutLayout,
                onLayout = model::changeNeovimShortcutLayout,
                effect = model.neovimShortcutEffect,
                onEffect = model::changeNeovimShortcutEffect,
                hapticsEnabled = model.neovimShortcutHaptics,
                onHaptics = model::changeNeovimShortcutHaptics,
                hazeState = workspaceHaze,
            )
        is SettingsRoute.ShortcutEditor -> {
            val id = route.id
            val profile = model.shortcutProfiles.firstOrNull { it.id == id }
            // A new profile gets its id once, so a failed save keeps the same draft.
            val saveId = remember(id) { profile?.id ?: ShortcutProfiles.newId() }
            key(id) {
                ShortcutProfileSheet(
                    profile,
                    onSave = { name, match, prefix, enter, text ->
                        model.saveShortcutProfile(saveId, name, match, prefix, enter, text)
                    },
                    onClose = { closeSettings() },
                    onDelete = profile?.let { { model.deleteShortcutProfile(it.id) } },
                    defaults =
                        when (id) {
                            ShortcutProfiles.NEOVIM -> NeovimShortcuts.defaults
                            ShortcutProfiles.SHELL -> ShortcutProfiles.shellDefaults
                            else -> null
                        },
                    hazeState = workspaceHaze,
                )
            }
        }
    }
    pendingShortcut?.let { (profile, item, target) ->
        ShortcutConfirmDialog(
            profile,
            item,
            onConfirm = {
                pendingShortcut = null
                client.shortcut(model.shortcutProfiles, profile, item, target)
            },
            onDismiss = { pendingShortcut = null },
        )
    }
    if (model.images.notice.isNotEmpty())
        AlertDialog(
            onDismissRequest = { model.images.notice = "" },
            title = { Text(stringResource(R.string.file_upload)) },
            text = { Text(model.images.notice) },
            confirmButton = {
                TextButton(onClick = { model.images.notice = "" }) {
                    Text(stringResource(R.string.label_ok))
                }
            },
        )
    if (forwards)
        PortForwardSheet(
            model.forwardServers(),
            model.activeServerId,
            workspaceHaze,
            onClose = { forwards = false },
            onStart = { id ->
                val saved = PortForwards.items.firstOrNull { it.id == id }
                val target =
                    model.forwardServers().firstOrNull {
                        it.profile.id == saved?.serverId && it.profile.endpoint == saved.endpoint
                    }
                if (saved != null && target != null && target.session.isNotBlank())
                    PortForwards.update(
                        saved.copy(session = target.session, server = target.profile.label),
                    )
                withUploadNetwork({ PortForwards.requestStart(context, id) })
            },
        ) { target, host, port, local ->
            withUploadNetwork(
                { PortForwards.start(context, target.profile, target.session, host, port, local) },
            )
        }
    if (servers) ServersSheet(model, hazeState = workspaceHaze) { servers = false }
    if (addServer)
        ServerEditor(
            null,
            onClose = { addServer = false },
            onSave = { label, url, icon -> model.saveServer(null, label, url, icon) },
        )
    // One drawer for sessions, tabs and panes; each entry point opens it on its own page.
    if (picker == "sessions" || picker == "tabs" || picker == "panes")
        TabPaneSwitcher(
            windows,
            window?.id.orEmpty(),
            chosen,
            state.programs,
            state.titles,
            state.directories,
            client::tabPaneIndex,
            ready,
            canCreateTab = ready && state.selectedSession.isNotBlank(),
            canCreatePane = ready && state.selectedSession.isNotBlank(),
            onTab = {
                picker = ""
                client.selectWindow(it)
            },
            onPane = {
                picker = ""
                openPane(it)
            },
            onNewTab = {
                picker = ""
                client.createTab()
            },
            onNewPane = { anchor, split ->
                picker = ""
                client.createPane(
                    split,
                    anchor,
                    keepTiled =
                        allowFloatingOverlay && tiles != null && floating.any { it.id == anchor },
                )
            },
            onCloseTab = {
                picker = ""
                closeTarget = closeTabTarget(it)
            },
            onClosePane = {
                picker = ""
                closeTarget = closePaneTarget(it)
            },
            onClose = { picker = "" },
            initialPage = if (picker == "panes") 1 else 0,
            onMoveTab = client::moveTab,
            onMovePane = client::movePane,
            reorderKey = currentSessionKey,
            onRenameTab = { tab, label ->
                client.editTerminal(displayedSession, tab, tab, "rename_tab", label = label)
            },
            onRenamePane = { tab, id, label ->
                client.editTerminal(displayedSession, tab, id, "rename_pane", label = label)
            },
            onFloatPane = { tab, id ->
                client.editTerminal(displayedSession, tab, id, "float_pane")
            },
            onDockPane = { tab, id, anchor, split ->
                client.editTerminal(
                    displayedSession,
                    tab,
                    id,
                    "dock_pane",
                    anchor = anchor,
                    split = split,
                )
            },
            error = state.error,
            hazeState = workspaceHaze,
            sessions =
                SwitcherSessions(
                    model.sessionTargets.size,
                    model.sessionTargets
                        .firstOrNull { it.key == currentSessionKey }
                        ?.let { "${it.session.label} · ${it.server.label}" }
                        .orEmpty(),
                    onShown = model::refreshSessionDetails,
                ) {
                    SessionsPage(
                        model.sessionTargets,
                        currentSessionKey,
                        model.lastSessionKey,
                        ready,
                        model.closingSessionKey.ifBlank { model.renamingSessionKey },
                        model.sessionActionError,
                        model::closeSession,
                        model::renameSession,
                        onSelect = {
                            picker = ""
                            model.selectSessionTarget(it)
                        },
                        onNew = {
                            picker = ""
                            newSession = true
                        },
                        allPanes = { back ->
                            DisposableEffect(model) {
                                model.showPaneCatalog(true)
                                onDispose { model.showPaneCatalog(false) }
                            }
                            AllPanesPage(
                                model.paneCatalogSources,
                                model.activeServerId,
                                displayedSession,
                                chosen,
                                back,
                                model::refreshPaneCatalog,
                            ) { target ->
                                model.openCatalogPane(target).also { opened ->
                                    if (opened) picker = ""
                                }
                            }
                        },
                    )
                },
            openSessions = picker == "sessions",
        )
    dockFloatingPane?.let { id ->
        if (allowFloatingOverlay && window != null && window.floatingPanes().any { it.id == id }) {
            DockPaneSheet(
                window,
                state.programs,
                state.titles,
                ready,
                workspaceHaze,
                onDock = { anchor, split ->
                    client.editTerminal(
                        displayedSession,
                        window.id,
                        id,
                        "dock_pane",
                        anchor = anchor,
                        split = split,
                    )
                },
                onClose = { dockFloatingPane = null },
            )
        }
    }
    LaunchedEffect(allowFloatingOverlay, window?.layout, dockFloatingPane) {
        if (
            !allowFloatingOverlay ||
                window?.layout?.any { it.id == dockFloatingPane && it.floating } != true
        ) {
            dockFloatingPane = null
        }
    }
    closeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { closeTarget = null },
            title = {
                Text(
                    if (target.tab) stringResource(R.string.close_tab_title)
                    else stringResource(R.string.close_tab),
                )
            },
            text = {
                Text(
                    if (!target.tab) stringResource(R.string.label_close_pane_confirm, target.label)
                    else if (target.blocks.size == 1)
                        stringResource(R.string.label_close_tab_confirm_one, target.label)
                    else
                        stringResource(
                            R.string.label_close_tab_confirm_many,
                            target.label,
                            target.blocks.size,
                        ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = ready,
                    onClick = {
                        closeTarget = null
                        if (target.tab) client.closeTab(target.session, target.id, target.blocks)
                        else client.closePane(target.session, target.id)
                    },
                ) {
                    Text(stringResource(R.string.close), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { closeTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (newSession)
        NewSessionDialog(model.activeServer.label, ready, client::createSession) {
            newSession = false
        }
}

@Composable
private fun WorkspaceBottomBar(
    model: RexModel,
    client: RexConnection,
    state: AppState,
    changingSession: Boolean,
    sideTabs: Boolean,
    extendUnderNav: Boolean,
    controlled: Boolean,
    ready: Boolean,
    keyboardVisible: Boolean,
    keyTarget: String,
    toolbarHeight: IntArray,
    onPaste: () -> Boolean,
    onKeyboard: () -> Unit,
    tabs: (@Composable () -> Unit)?,
) {
    // Square beside a side tab rail; the bottom tab bar keeps its rounded top.
    val shape =
        if (sideTabs) androidx.compose.ui.graphics.RectangleShape
        else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (extendUnderNav) Modifier
                else
                    Modifier.windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                    ),
            ),
    ) {
        if (!sideTabs && tabs != null) tabs()
        Box(
            Modifier.fillMaxWidth()
                .then(
                    if (model.toolbarWithKeyboardOnly)
                        Modifier.keyboardRevealed(WindowInsets.ime, WindowInsets.navigationBars) {
                            toolbarHeight[0] = it
                        }
                    else Modifier,
                ),
        ) {
            KeyboardBar(
                controlled,
                ready,
                client.controlModifier,
                client.altModifier,
                client.shiftModifier,
                keyboardVisible,
                client::toggleControl,
                client::toggleAlt,
                client::toggleShift,
                client::pressShift,
                client::releaseShift,
                { client.keyForTarget(it, keyTarget) },
                readable = state.connected,
                onControlShortcut = { client.controlShortcut(it, keyTarget) },
                inputKey = keyTarget,
                shortcutGesture = model.shortcutGesture,
                arrowJoystickEnabled = model.arrowJoystickEnabled,
                onPaste = { onPaste() },
                onPrompt =
                    if (client.hasSemanticPrompts && !changingSession) client::scrollPrompt
                    else null,
                toolbarKeys = model.toolbarKeys,
                controlItems = model.controlItems,
                onKeyboard = onKeyboard,
            )
        }
    }
}

private fun getClipboard(context: Context) = context.getSystemService(Context.CLIPBOARD_SERVICE)

/** The drawer's preferred height for the sessions page; filtering never resizes it. */
internal fun sessionsPageHeight(targets: List<SessionTarget>) =
    (128 +
            targets.size.coerceIn(1, 7) * 64 +
            targets.map { it.server.id }.distinct().size.coerceAtMost(5) * 24)
        .dp
        .coerceIn(248.dp, 600.dp)

/**
 * Sessions grouped by server, with search, New session, rename and close. A page of
 * [TabPaneSwitcher].
 */
@Composable
internal fun SessionsPage(
    targets: List<SessionTarget>,
    current: String,
    last: String,
    canCreate: Boolean,
    closing: String,
    error: String,
    onCloseSession: (SessionTarget) -> Unit,
    onRenameSession: (SessionTarget, String) -> Boolean,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    allPanes: (@Composable (onBack: () -> Unit) -> Unit)? = null,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var showAllPanes by rememberSaveable { mutableStateOf(false) }
    if (showAllPanes && allPanes != null) {
        allPanes { showAllPanes = false }
        return
    }
    var closeTarget by remember { mutableStateOf<SessionTarget?>(null) }
    var renameTarget by remember { mutableStateOf<SessionTarget?>(null) }
    var revealedRow by rememberSaveable { mutableStateOf<String?>(null) }
    val groups =
        remember(targets, current, search) { SessionCatalog.groups(targets, current, search) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SwitcherSearchField(
                search,
                { search = it },
                stringResource(R.string.find_session),
                Modifier.weight(1f),
            )
            if (allPanes != null) {
                TextButton(
                    onClick = { showAllPanes = true },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.all_panes),
                        style = SettingsType.section,
                        maxLines = 1,
                    )
                }
            }
            SwitcherAddButton(stringResource(R.string.new_session), canCreate, onNew)
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (groups.isEmpty())
                item {
                    Text(
                        if (targets.isEmpty()) stringResource(R.string.no_sessions_yet)
                        else stringResource(R.string.no_matching_sessions),
                        Modifier.padding(vertical = 16.dp),
                        style = SettingsType.row,
                        color = scheme.onSurfaceVariant,
                    )
                }
            groups.forEach { group ->
                item(key = "server:${group.server.id}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ServerGlyph(group.server.icon, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            group.server.label,
                            Modifier.weight(1f),
                            style = SettingsType.section,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            group.sessions.size.toString(),
                            style = SettingsType.detail,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                items(group.sessions, key = { it.key }) { target ->
                    SessionRow(
                        target,
                        current == target.key,
                        last == target.key,
                        closing == target.key,
                        onSelect = { onSelect(target.key) },
                        onClose = { closeTarget = target },
                        onRename = { renameTarget = target },
                        revealed = revealedRow == target.key,
                        onReveal = { open ->
                            if (open) revealedRow = target.key
                            else if (revealedRow == target.key) revealedRow = null
                        },
                    )
                }
            }
        }
        if (error.isNotBlank())
            Text(
                error,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = scheme.error,
                style = SettingsType.detail,
            )
    }
    renameTarget?.let { target ->
        SessionRenameDialog(
            target,
            closing.isBlank(),
            onSave = { label -> if (onRenameSession(target, label)) renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    closeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { closeTarget = null },
            title = { Text(stringResource(R.string.close_session_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.close_session_confirm,
                        target.session.label,
                        target.server.label,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        closeTarget = null
                        onCloseSession(target)
                    },
                    enabled = closing.isBlank(),
                ) {
                    Text(
                        stringResource(R.string.close_session),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { closeTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LiveTerminal(
    client: RexConnection,
    blocks: List<BlockItem>,
    chosen: String,
    liveBlock: String,
    programs: Map<String, ProgramIdentity>,
    controlled: Boolean,
    fontSize: Float,
    onInput: (String) -> Unit,
    onScroll: (Int) -> Unit,
    onWheel: (Int, Int, Int, Int, Int) -> Unit,
    onActivate: (Boolean) -> Unit,
    onBottom: () -> Unit,
    onDimensions: (Int, Int) -> Unit,
    onSelect: (String) -> Unit,
    onPanes: () -> Unit,
    onView: (TerminalView) -> Unit,
    modifier: Modifier = Modifier,
    floatingDock: Boolean = false,
    paneLayout: List<PanePlacement> = emptyList(),
    titles: Map<String, String> = emptyMap(),
    directories: Map<String, String> = emptyMap(),
    hazeState: HazeState? = null,
    captureBackdrop: Boolean = false,
    onImage: (android.net.Uri, () -> Unit) -> Boolean = { _, release ->
        release()
        false
    },
    onPaste: () -> Boolean = { false },
    padding: TerminalPadding = TerminalPadding.NORMAL,
    extendPadding: Boolean = true,
    imeEnterSubmits: Boolean = false,
    keyboardChrome: () -> Int = { 0 },
    squareCorners: Boolean = false,
    extendsUnderNavigation: Boolean = false,
    navigationClearance: Dp = 0.dp,
    bottomOverlayInset: Dp = 0.dp,
) {
    val layout by client.displayLayout.collectAsState()
    val screen = client.screenForLayout(layout)
    TerminalCarousel(
        blocks,
        chosen,
        liveBlock,
        screen,
        { client.paneFrames[it] },
        programs,
        controlled,
        fontSize,
        onInput,
        onScroll,
        onWheel,
        onActivate,
        onBottom,
        onDimensions,
        onSelect,
        onPanes,
        onView,
        modifier,
        client::bindTerminalView,
        onMouseClick = client::click,
        mouseTarget = client::keyTarget,
        onImage = onImage,
        onPaste = onPaste,
        imageTarget = client::imageInputTarget,
        bottomControlsInset = if (floatingDock) 56.dp else 0.dp,
        paneLayout = paneLayout,
        titles = titles,
        directories = directories,
        hazeState = hazeState,
        captureBackdrop = captureBackdrop,
        padding = padding,
        extendPadding = extendPadding,
        imeEnterSubmits = imeEnterSubmits,
        keyboardChrome = keyboardChrome,
        squareCorners = squareCorners,
        extendsUnderNavigation = extendsUnderNavigation,
        navigationClearance = navigationClearance,
        bottomOverlayInset = bottomOverlayInset,
    )
}

@Composable
private fun BackgroundAlertsDialog(
    model: RexModel,
    hazeState: HazeState? = null,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                model.setBackgroundAlerts(true)
                denied = false
            } else denied = true
        }
    SettingsDrawer(
        stringResource(R.string.background_alerts),
        stringResource(R.string.a11y_close_background_alerts),
        onClose,
        hazeState,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.background_alerts_desc_1), style = SettingsType.body)
            Text(stringResource(R.string.background_alerts_desc_2), style = SettingsType.body)
            Text(
                stringResource(R.string.background_alerts_desc_3),
                style = SettingsType.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.notify_task_updates),
                    Modifier.weight(1f),
                    style = SettingsType.row,
                )
                Switch(
                    checked = model.backgroundAlerts,
                    onCheckedChange = { enabled ->
                        if (!enabled) model.setBackgroundAlerts(false)
                        else if (
                            Build.VERSION.SDK_INT >= 33 &&
                                context.checkSelfPermission(
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        )
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else if (BackgroundAlerts.allowed(context)) {
                            model.setBackgroundAlerts(true)
                            denied = false
                        } else denied = true
                    },
                    enabled = model.servers.any { model.serverStatuses[it.id]?.maintained == true },
                )
            }
            if (model.servers.none { model.serverStatuses[it.id]?.maintained == true })
                Text(
                    stringResource(R.string.connect_server_for_alerts),
                    style = SettingsType.detail,
                )
            if (denied || (model.backgroundAlerts && !BackgroundAlerts.allowed(context))) {
                Text(stringResource(R.string.allow_notifications_hint), style = SettingsType.detail)
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                ) {
                    Text(stringResource(R.string.notification_settings))
                }
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.done))
        }
    }
}
