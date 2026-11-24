package org.etg.migration

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.etg.migration.ui.EtgTab
import org.etg.migration.ui.EtgViewModel
import org.etg.migration.ui.components.OperationProgressDialog
import org.etg.migration.ui.screens.ExportScreen
import org.etg.migration.ui.screens.GuideAndSettingsScreen
import org.etg.migration.ui.screens.ImportScreen
import org.etg.migration.ui.screens.TransferScreen
import org.etg.migration.ui.theme.EtgLanguagePreference
import org.etg.migration.ui.theme.EtgTheme
import org.etg.migration.ui.theme.GrapheneLogoMark
import org.etg.migration.ui.theme.GrapheneMenuBar
import org.etg.migration.ui.theme.GrapheneMenuBarActive
import org.etg.migration.ui.theme.LocalGrapheneColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val vm: EtgViewModel = viewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()

            EtgTheme(
                themePreference = state.themePref,
                languagePreference = state.langPref
            ) {
                EtgRootScreen(viewModel = vm)
            }
        }
    }
}

private data class NavDestination(
    val tab: EtgTab,
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val labelRes: Int
)

private val NAV_DESTINATIONS = listOf(
    NavDestination(EtgTab.EXPORT, R.drawable.ic_upload, R.string.nav_export),
    NavDestination(EtgTab.IMPORT, R.drawable.ic_download, R.string.nav_import),
    NavDestination(EtgTab.TRANSFER, R.drawable.ic_swap, R.string.nav_transfer),
    NavDestination(EtgTab.GUIDE, R.drawable.ic_shield, R.string.nav_guide)
)

@Composable
private fun EtgRootScreen(viewModel: EtgViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val g = LocalGrapheneColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResumeRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.statusBannerMessage) {
        val msg = state.statusBannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(g.background)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(14.dp),
                        containerColor = if (g.isDark) g.surfaceHigh else g.textPrimary,
                        contentColor = if (g.isDark) g.textPrimary else g.surface,
                        actionColor = g.accentCyan
                    )
                }
            },
            topBar = {
                EtgTopBar(
                    langPref = state.langPref,
                    onToggleLanguage = { next -> viewModel.setLangPref(next) }
                )
            },
            bottomBar = {
                EtgBottomBar(
                    selected = state.selectedTab,
                    onSelect = { viewModel.selectTab(it) }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
            ) {
                AnimatedContent(
                    targetState = state.selectedTab,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 40)) +
                            slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 28 }) togetherWith
                            fadeOut(tween(120))
                    },
                    label = "tabTransition"
                ) { tab ->
                    when (tab) {
                        EtgTab.EXPORT -> ExportScreen(state = state, viewModel = viewModel)
                        EtgTab.IMPORT -> ImportScreen(state = state, viewModel = viewModel)
                        EtgTab.TRANSFER -> TransferScreen(state = state, viewModel = viewModel)
                        EtgTab.GUIDE -> GuideAndSettingsScreen(state = state, viewModel = viewModel)
                    }
                }
            }
        }
    }

    val issueSession = state.issueSession
    if (issueSession == null) {
        OperationProgressDialog(
            progress = state.progress,
            onCancel = { viewModel.cancelActiveOperation() }
        )
    } else {
        org.etg.migration.ui.components.IssueDialog(
            session = issueSession,
            onSkip = viewModel::skipIssue,
            onRetry = viewModel::retryIssue,
            onClose = viewModel::dismissIssues
        )
    }
}

@Composable
private fun EtgTopBar(
    langPref: EtgLanguagePreference,
    onToggleLanguage: (EtgLanguagePreference) -> Unit
) {
    val g = LocalGrapheneColors.current
    val nextLang = when (langPref) {
        EtgLanguagePreference.PL -> EtgLanguagePreference.EN
        EtgLanguagePreference.EN -> EtgLanguagePreference.PL
        EtgLanguagePreference.SYSTEM -> EtgLanguagePreference.PL
    }
    val langBadge = when (langPref) {
        EtgLanguagePreference.PL -> "PL"
        EtgLanguagePreference.EN -> "EN"
        EtgLanguagePreference.SYSTEM -> "AUTO"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GrapheneMenuBar)
            .statusBarsPadding()
            .height(56.dp)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GrapheneLogoMark(iconSize = 34.dp, color = Color.White)
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.app_full_name),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { onToggleLanguage(nextLang) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = langBadge, style = MaterialTheme.typography.labelLarge, color = GrapheneMenuBarActive)
        }
    }
}

@Composable
private fun EtgBottomBar(
    selected: EtgTab,
    onSelect: (EtgTab) -> Unit
) {
    val g = LocalGrapheneColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(g.surface.copy(alpha = if (g.isDark) 0.96f else 0.98f))
    ) {
        HorizontalDivider(thickness = 1.dp, color = g.border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(72.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NAV_DESTINATIONS.forEach { dest ->
                BottomBarItem(
                    destination = dest,
                    selected = dest.tab == selected,
                    onClick = { onSelect(dest.tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    destination: NavDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = LocalGrapheneColors.current
    val indicatorAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "navInd")
    val iconColor by animateColorAsState(if (selected) g.accentCyan else g.textTertiary, tween(200), label = "navIcon")
    val labelColor by animateColorAsState(if (selected) g.textPrimary else g.textTertiary, tween(200), label = "navLabel")
    val indicatorWidth by animateFloatAsState(if (selected) 1f else 0.6f, tween(260, easing = FastOutSlowInEasing), label = "navW")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                role = Role.Tab,
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .width(58.dp * indicatorWidth)
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(g.accentContainer.copy(alpha = indicatorAlpha))
            )
            Icon(
                painter = painterResource(destination.iconRes),
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(21.dp)
            )
        }
        Text(
            text = stringResource(destination.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
