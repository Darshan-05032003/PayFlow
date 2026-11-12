package com.darshan.payflow.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.payflow.BuildConfig
import com.darshan.payflow.R
import com.darshan.payflow.ui.PayFlowTestTags
import com.darshan.payflow.ui.designsystem.components.AvatarRing
import com.darshan.payflow.ui.designsystem.components.DisplayText
import com.darshan.payflow.ui.designsystem.components.GhostButton
import com.darshan.payflow.ui.designsystem.components.GhostTone
import com.darshan.payflow.ui.designsystem.components.HairlineDivider
import com.darshan.payflow.ui.designsystem.components.PayFlowAvatar
import com.darshan.payflow.ui.designsystem.components.PayFlowBottomSheet
import com.darshan.payflow.ui.designsystem.components.PayFlowChoiceChip
import com.darshan.payflow.ui.designsystem.components.PayFlowListRow
import com.darshan.payflow.ui.designsystem.components.PayFlowSurface
import com.darshan.payflow.ui.designsystem.components.PayFlowSwitch
import com.darshan.payflow.ui.designsystem.components.PayFlowTopBar
import com.darshan.payflow.ui.designsystem.components.PrimaryButton
import com.darshan.payflow.ui.designsystem.components.SectionHeader
import com.darshan.payflow.ui.designsystem.components.SkeletonBlock
import com.darshan.payflow.ui.designsystem.components.SurfaceLevel
import com.darshan.payflow.ui.designsystem.effects.ThemeMode
import com.darshan.payflow.ui.designsystem.tokens.Radii
import com.darshan.payflow.ui.designsystem.tokens.Sizes
import com.darshan.payflow.ui.designsystem.tokens.Spacing
import com.darshan.payflow.ui.navigation.LocalBottomBarInset
import com.darshan.payflow.ui.navigation.ScrollToTopOnReselect
import com.darshan.payflow.ui.navigation.ShellRoute
import com.darshan.payflow.ui.theme.PayFlowTheme

data class MeCallbacks(
    val onSecurity: () -> Unit = {},
    val onReceive: () -> Unit = {},
    val onHaptics: (Boolean) -> Unit = {},
    val onSound: (Boolean) -> Unit = {},
    val onReduceMotion: (Boolean) -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
)

@Composable
fun MeRoute(onSecurity: () -> Unit, onReceive: () -> Unit, viewModel: MeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MeScreen(
        state,
        MeCallbacks(
            onSecurity = onSecurity,
            onReceive = onReceive,
            onHaptics = viewModel::setHaptics,
            onSound = viewModel::setSound,
            onReduceMotion = viewModel::setReduceMotion,
            onTheme = viewModel::setTheme,
        ),
    )
}

/**
 * Who you are on UPI, the doors to Security and Receive, and how the app feels. The one primary action is setting up a UPI
 * ID, and only while there isn't one. Toggles act at once and app-wide; nothing here needs a save button.
 */
@Composable
fun MeScreen(state: MeUiState, callbacks: MeCallbacks, version: String = BuildConfig.VERSION_NAME) {
    val colors = PayFlowTheme.colors
    val scroll = rememberScrollState()
    val barInset = LocalBottomBarInset.current
    ScrollToTopOnReselect(ShellRoute.Me, scroll)
    var aboutOpen by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(colors.bg).testTag(PayFlowTestTags.ME_SCREEN)) {
        Column(Modifier.fillMaxSize().then(if (aboutOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
            PayFlowTopBar(title = stringResource(R.string.me_title), large = true)
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ProfileCard(state, callbacks.onReceive)
                SectionHeader(stringResource(R.string.me_section_account))
                Group {
                    LinkRow(
                        icon = Icons.Outlined.Shield,
                        title = stringResource(R.string.me_security),
                        subtitle = stringResource(if (state.pinConfigured) R.string.me_security_on else R.string.me_security_off),
                        tag = PayFlowTestTags.ME_SECURITY,
                        onClick = callbacks.onSecurity,
                    )
                    HairlineDivider()
                    LinkRow(Icons.Outlined.QrCode2, stringResource(R.string.me_receive), stringResource(R.string.me_receive_hint), PayFlowTestTags.ME_RECEIVE, callbacks.onReceive)
                }
                SectionHeader(stringResource(R.string.me_section_feel))
                Group {
                    Toggle(stringResource(R.string.me_haptics), stringResource(R.string.me_haptics_hint), state.settings.hapticsEnabled, PayFlowTestTags.ME_HAPTICS, callbacks.onHaptics)
                    HairlineDivider()
                    Toggle(stringResource(R.string.me_sound), stringResource(R.string.me_sound_hint), state.settings.soundEnabled, PayFlowTestTags.ME_SOUND, callbacks.onSound)
                    HairlineDivider()
                    Toggle(stringResource(R.string.me_reduce_motion), stringResource(R.string.me_reduce_motion_hint), state.settings.reduceMotion, PayFlowTestTags.ME_REDUCE_MOTION, callbacks.onReduceMotion)
                    HairlineDivider()
                    ThemePicker(state.settings.theme, callbacks.onTheme)
                }
                SectionHeader(stringResource(R.string.me_section_more))
                Group {
                    LinkRow(Icons.Outlined.Info, stringResource(R.string.me_about), stringResource(R.string.me_about_hint, version), PayFlowTestTags.ME_ABOUT) { aboutOpen = true }
                }
                Spacer(Modifier.height(Spacing.xl + barInset).navigationBarsPadding())
            }
        }
        AboutSheet(aboutOpen, version) { aboutOpen = false }
    }
}

@Composable
private fun ProfileCard(state: MeUiState, onSetUp: () -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    PayFlowSurface(Modifier.fillMaxWidth().testTag(PayFlowTestTags.ME_PROFILE), level = SurfaceLevel.Hero, shape = Radii.largeShape) {
        val profile = state.profile
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBlock(Modifier.size(Sizes.avatarLarge), shape = Radii.pillShape, base = colors.onHero.copy(alpha = SKELETON_ALPHA), highlight = colors.onHero.copy(alpha = SKELETON_HIGHLIGHT))
                    SkeletonBlock(Modifier.fillMaxWidth().height(Sizes.chipHeight), base = colors.onHero.copy(alpha = SKELETON_ALPHA), highlight = colors.onHero.copy(alpha = SKELETON_HIGHLIGHT))
                }
                profile != null -> Row(
                    Modifier.semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PayFlowAvatar(profile.name, size = Sizes.avatarLarge, ring = AvatarRing.Accent)
                    Column(Modifier.weight(1f)) {
                        Text(DisplayText.safe(profile.name), style = type.title, color = colors.onHero, maxLines = 2)
                        Text(profile.vpa, style = type.label, color = colors.onHero.copy(alpha = HERO_SECONDARY_ALPHA), maxLines = 1)
                    }
                }
                else -> {
                    Text(stringResource(R.string.me_profile_none), style = type.title, color = colors.onHero, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.me_profile_none_hint), style = type.body, color = colors.onHero.copy(alpha = HERO_SECONDARY_ALPHA))
                    PrimaryButton(stringResource(R.string.me_profile_setup), onSetUp, Modifier.fillMaxWidth().testTag(PayFlowTestTags.ME_SETUP_PROFILE))
                }
            }
        }
    }
}

private const val HERO_SECONDARY_ALPHA = 0.72f
private const val SKELETON_ALPHA = 0.10f
private const val SKELETON_HIGHLIGHT = 0.18f

@Composable
private fun Group(content: @Composable () -> Unit) {
    PayFlowSurface(Modifier.fillMaxWidth(), level = SurfaceLevel.Card) { Column { content() } }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    val colors = PayFlowTheme.colors
    PayFlowListRow(
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = Int.MAX_VALUE,
        modifier = Modifier.testTag(tag),
        leading = {
            Box(Modifier.size(Sizes.avatar).background(colors.accentTint, Radii.pillShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.onAccentTint, modifier = Modifier.size(Sizes.icon))
            }
        },
        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(Sizes.icon)) },
        onClick = onClick,
    )
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)) {
        PayFlowSwitch(checked, onChange, title, Modifier.fillMaxWidth().testTag(tag))
        Text(hint, style = PayFlowTheme.type.label, color = PayFlowTheme.colors.textSecondary, modifier = Modifier.padding(bottom = Spacing.xxs))
    }
}

@Composable
private fun ThemePicker(selected: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.me_theme), style = PayFlowTheme.type.body, color = PayFlowTheme.colors.text)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ThemeMode.entries.forEach { mode ->
                val label = stringResource(
                    when (mode) {
                        ThemeMode.System -> R.string.me_theme_system
                        ThemeMode.Light -> R.string.me_theme_light
                        ThemeMode.Dark -> R.string.me_theme_dark
                    },
                )
                PayFlowChoiceChip(label, selected = mode == selected, onClick = { onTheme(mode) }, modifier = Modifier.testTag(PayFlowTestTags.meTheme(mode.name)))
            }
        }
    }
}

@Composable
private fun AboutSheet(visible: Boolean, version: String, onDismiss: () -> Unit) {
    val colors = PayFlowTheme.colors
    val type = PayFlowTheme.type
    PayFlowBottomSheet(visible = visible, onDismissRequest = onDismiss, modifier = Modifier.testTag(PayFlowTestTags.ME_ABOUT_SHEET)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.me_about), style = type.title, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.me_about_hint, version), style = type.label, color = colors.textSecondary)
            Text(stringResource(R.string.about_body), style = type.body, color = colors.text)
            Text(stringResource(R.string.security_sandbox_note), style = type.label, color = colors.textSecondary)
            Text(stringResource(R.string.about_fonts), style = type.label, color = colors.textSecondary)
            Text(stringResource(R.string.about_inspired), style = type.label, color = colors.textSecondary)
            GhostButton(stringResource(R.string.about_close), onDismiss, Modifier.fillMaxWidth(), outlined = false, tone = GhostTone.Neutral)
        }
    }
}
