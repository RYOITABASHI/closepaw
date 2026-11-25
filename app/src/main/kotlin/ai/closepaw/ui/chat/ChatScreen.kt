package ai.closepaw.ui.chat

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import ai.closepaw.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.closepaw.app.AgentService
import ai.closepaw.app.McpServerController
import ai.closepaw.protocol.PlatformMode
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import ai.closepaw.history.model.SessionInfo
import ai.closepaw.ui.capsule.surface.smartCapsuleHostPadding
import ai.closepaw.onboarding.PermissionStateMonitor.PermissionRepairModel
import ai.closepaw.ui.chat.components.ChatHeader
import ai.closepaw.ui.chat.components.MessageBubble
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.navigation.NavigationDrawerContent
import ai.closepaw.ui.onboarding.PermissionRepairCard
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.closePaw
import ai.closepaw.ui.theme.paperGrain
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class VoiceFeedbackAlert(
    val id: Long,
    val message: String,
)

/**
 * ChatScreen - Main chat interface composable.
 *
 * Orchestrates all chat components into a cohesive conversation experience.
 * Includes navigation drawer for session history and settings access.
 * Uses SmartCapsuleSurface as bottomBar (replaces old InputDock).
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    sessions: List<SessionInfo>,
    effectivePlatformMode: PlatformMode?,
    onOpenSettings: () -> Unit,
    onSessionSelect: (SessionInfo) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (SessionInfo) -> Unit,
    onLoadSessions: () -> Unit,
    onOpenViewer: () -> Unit,
    onOpenApp: (String) -> Unit,
    repairModel: PermissionRepairModel? = null,
    onFixAccessibility: () -> Unit = {},
    onFixOverlay: () -> Unit = {},
    onFixBattery: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages = viewModel.messages
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val mcpState by McpServerController.state.collectAsStateWithLifecycle()
    val status by AgentService.statusFlow.collectAsStateWithLifecycle()

    // Load sessions when drawer opens
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            onLoadSessions()
        }
    }
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier.fillMaxSize(),
        drawerContent = {
            NavigationDrawerContent(
                sessions = sessions,
                onSessionSelect = { session ->
                    scope.launch { drawerState.close() }
                    onSessionSelect(session)
                },
                onNewSession = {
                    scope.launch { drawerState.close() }
                    onNewSession()
                },
                onDeleteSession = onDeleteSession,
                onSettingsClick = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                onClose = {
                    scope.launch { drawerState.close() }
                }
            )
        },
        gesturesEnabled = true
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // Draw one continuous grain pass behind every Scaffold slot so the
            // masthead, page body, dock, and capsule shell share the same paper
            // without placing grain over text or controls.
            Box(modifier = Modifier.matchParentSize().paperGrain())

            // Empty-state paw mark — hoisted out of EmptyState so its TOP can
            // bleed up through the masthead band (Scaffold renders ON TOP, so
            // "ClosePaw" wordmark stays in front). Positioned low enough that
            // the paw body never lands on title text. Uses ic_paw_hero (thin
            // stroke) and the Claw brand accent so the empty page isn't all
            // ink-on-paper.
            if (messages.isEmpty() && uiState.showEmptyState) {
                Icon(
                    painter = painterResource(R.drawable.ic_paw_hero),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 48.dp, y = 35.dp)
                        .size(300.dp),
                )
            }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = Color.Transparent,
                topBar = {
                    ChatHeader(
                        onMenuClick = {
                            scope.launch { drawerState.open() }
                        },
                        showNewChatButton = false,
                    )
                },
                bottomBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .navigationBarsPadding()
                            .smartCapsuleHostPadding()
                    ) {
                        if (repairModel != null) {
                            PermissionRepairCard(
                                model = repairModel,
                                onFixAccessibility = onFixAccessibility,
                                onFixOverlay = onFixOverlay,
                                onFixBattery = onFixBattery,
                            )
                        }
                    }
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    if (messages.isEmpty() && uiState.showEmptyState) {
                        Column(
                            modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val mcpStatus = when (val current = mcpState) {
                                McpServerController.State.Off ->
                                    stringResource(R.string.session_home_mcp_off)
                                is McpServerController.State.Running ->
                                    stringResource(R.string.mcp_server_status_running, current.port)
                                is McpServerController.State.Failed ->
                                    stringResource(R.string.mcp_server_status_failed, current.reason)
                            }
                            Text(mcpStatus, style = MaterialTheme.typography.titleMedium)
                            if (status.isNotBlank()) Text(status)
                            Button(onClick = onOpenSettings) {
                                Text(stringResource(R.string.navigation_drawer_settings))
                            }
                            if (effectivePlatformMode == PlatformMode.VIRTUAL_DISPLAY) {
                                TextButton(onClick = onOpenViewer) {
                                    Text(stringResource(R.string.session_home_open_viewer))
                                }
                            }
                        }
                    } else if (messages.isNotEmpty()) {
                        // Message list
                        MessageList(
                            messages = messages,
                            onOpenApp = onOpenApp,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/**
 * MessageList - Scrollable list of chat messages with stick-to-bottom policy.
 *
 * Auto-scrolls only when the user is near the bottom of the list (following the
 * conversation). Shows a scroll-to-bottom FAB when user has scrolled up and new
 * content arrives below the fold.
 */
@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Follow mode: true = auto-scroll with new content, false = user scrolled up.
    var followMode by remember { mutableStateOf(true) }
    // Suppress follow-mode detection during programmatic scrolls.
    var programmaticScroll by remember { mutableStateOf(false) }

    // Detect user scroll gestures to toggle follow mode.
    LaunchedEffect(Unit) {
        snapshotFlow {
            listState.isScrollInProgress to listState.canScrollForward
        }.collect { (scrolling, canForward) ->
            if (programmaticScroll) return@collect
            if (scrolling && canForward) {
                followMode = false
            } else if (!canForward && !scrolling) {
                followMode = true
            }
        }
    }

    // Derive a scroll key that changes on message count *and* last-message content.
    // This covers new messages, streaming text, AND action-card state changes.
    val scrollKey by remember {
        derivedStateOf {
            val last = messages.lastOrNull()
            val contentSignal = when (last) {
                is ChatMessage.Agent -> {
                    var signal = last.contentBlocks.size.toLong()
                    for (block in last.contentBlocks) {
                        when (block) {
                            is ContentBlock.Text -> signal += block.text.length
                            is ContentBlock.FinalText -> signal += block.text.length
                            is ContentBlock.Thought -> signal += block.text.length
                            is ContentBlock.Action -> signal += block.data.state.ordinal +
                                (block.data.resultSummary?.length ?: 0)
                        }
                    }
                    signal
                }
                is ChatMessage.User -> last.text.length.toLong()
                null -> 0L
            }
            messages.size.toLong() * 100_000 + contentSignal
        }
    }

    // Auto-scroll to actual bottom when content changes and we're following.
    LaunchedEffect(scrollKey) {
        if (messages.isNotEmpty() && followMode) {
            programmaticScroll = true
            listState.scrollToItem(messages.size - 1, Int.MAX_VALUE)
            programmaticScroll = false
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(
                items = messages,
                key = { it.id }
            ) { message ->
                MessageBubble(
                    message = message,
                    onOpenApp = onOpenApp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Scroll-to-bottom 'live' pill (Track A §5.1) when user has scrolled up.
        AnimatedVisibility(
            visible = !followMode && messages.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(ClosePawMotion.StatusFlip)),
            exit = fadeOut(animationSpec = tween(ClosePawMotion.StatusFlip)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .testTag("qa-live-pill")
                    .clickable {
                        followMode = true
                        scope.launch {
                            programmaticScroll = true
                            listState.scrollToItem(messages.size - 1, Int.MAX_VALUE)
                            programmaticScroll = false
                        }
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.chat_screen_jump_to_live),
                        style = MaterialTheme.closePaw.monoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Routes a Done/response tap from chat through the capsule before falling
 * back to the ViewModel send. Pinned by ChatDoneBridgeTest — the capsule
 * step must run first so a stale WaitingFor* state clears (see commit
 * d23537e8).
 */
internal fun forwardUserResponse(
    binding: ai.closepaw.ui.capsule.CapsuleBinding,
    sendUserResponse: (String, String) -> Unit,
    callId: String,
    response: String,
) {
    if (binding.onUserResponseSent(callId)) {
        sendUserResponse(callId, response)
    }
}
