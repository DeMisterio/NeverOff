package com.neveroff.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.neveroff.R
import com.neveroff.util.SettingsManager
import com.neveroff.util.ThemePreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SettingsCategory {
    MAIN, APPEARANCE, ANIMATIONS, PRIVACY, LANGUAGE, DEVELOPER
}

/**
 * Presets backing the "Animation Smoothness" selector — replaces the old raw bounciness
 * slider with a handful of named feels, each mapping to (allAnimations, bounciness).
 */
private enum class AnimationSmoothness(val label: String, val allAnimations: Boolean, val bounciness: Float) {
    NONE("No animation", false, 0.1f),
    BIT_SMOOTH("A bit smooth", true, 0.2f),
    SMOOTH("Smooth", true, 0.55f),
    APPLE("Apple smoothness", true, 0.9f);

    companion object {
        fun resolve(allAnimations: Boolean, bounciness: Float): AnimationSmoothness {
            if (!allAnimations) return NONE
            return values().filter { it.allAnimations }.minByOrNull { kotlin.math.abs(it.bounciness - bounciness) } ?: SMOOTH
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentProfile: Profile?,
    onBack: () -> Unit,
    onProfileUpdated: (Profile) -> Unit,
    onLogout: () -> Unit,
    onOpenUsernames: () -> Unit = {}
) {
    var activeCategory by remember { mutableStateOf(SettingsCategory.MAIN) }
    var showEditProfile by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (activeCategory) {
                            SettingsCategory.MAIN -> "Settings"
                            SettingsCategory.APPEARANCE -> "Appearance"
                            SettingsCategory.ANIMATIONS -> "Animations"
                            SettingsCategory.PRIVACY -> "Privacy & Security"
                            SettingsCategory.LANGUAGE -> "Language"
                            SettingsCategory.DEVELOPER -> "Developer Settings"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (activeCategory == SettingsCategory.MAIN) {
                                onBack()
                            } else if (activeCategory == SettingsCategory.ANIMATIONS) {
                                activeCategory = SettingsCategory.APPEARANCE
                            } else {
                                activeCategory = SettingsCategory.MAIN
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (activeCategory) {
                SettingsCategory.MAIN -> MainSettingsCategory(
                    onOpenUsernames = onOpenUsernames,
                    profile = currentProfile,
                    onOpenCategory = { activeCategory = it },
                    onEditProfile = { showEditProfile = true },
                    onLogout = { showLogoutConfirm = true }
                )
                SettingsCategory.APPEARANCE -> AppearanceCategoryScreen(
                    onOpenAnimations = { activeCategory = SettingsCategory.ANIMATIONS }
                )
                SettingsCategory.ANIMATIONS -> AnimationsCategoryScreen()
                SettingsCategory.PRIVACY -> PrivacyCategoryScreen()
                SettingsCategory.LANGUAGE -> LanguageCategoryScreen()
                SettingsCategory.DEVELOPER -> DeveloperCategoryScreen()
            }
        }
    }

    if (showEditProfile && currentProfile != null) {
        EditProfileBottomSheet(
            isVisible = showEditProfile,
            profile = currentProfile,
            onDismiss = { showEditProfile = false },
            onSave = { newName, newBio ->
                val updated = currentProfile.copy(display_name = newName, bio = newBio)
                onProfileUpdated(updated)
                showEditProfile = false
            },
            onPickAvatar = {}
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.log_out)) },
            text = { Text(stringResource(R.string.you_ll_need_to_sign_in)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) {
                    Text(stringResource(R.string.log_out_2), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun MainSettingsCategory(
    profile: Profile?,
    onOpenCategory: (SettingsCategory) -> Unit,
    onEditProfile: () -> Unit,
    onLogout: () -> Unit,
    onOpenUsernames: () -> Unit = {}
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // What the moderation console currently has applied to this account.
        // Renders nothing when there is nothing to say, so it costs the
        // overwhelming majority of users an empty item and no more. Placed
        // first because a greyed-out control elsewhere is only fair if the
        // explanation is somewhere obvious.
        item { com.neveroff.ui.components.AccountRestrictionsCard() }

        // Profile Card
        item {
            val editBlocked = com.neveroff.ui.components.isFeatureRestricted(
                com.neveroff.data.remote.EnforcementState.Feature.EDIT_PROFILE
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (editBlocked) 0.38f else 1f)
                    .clickable {
                        if (editBlocked) {
                            com.neveroff.ui.components.AppToast.show(
                                context.getString(
                                    R.string.restriction_blocked_toast,
                                    context.getString(R.string.feature_edit_profile)
                                )
                            )
                        } else onEditProfile()
                    },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .border(
                                width = 2.dp,
                                brush = androidx.compose.ui.graphics.Brush.linearGradient(com.neveroff.ui.theme.AccentGradient),
                                shape = CircleShape
                            )
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (profile?.avatar_url != null) {
                            AsyncImage(
                                model = profile.avatar_url,
                                contentDescription = stringResource(R.string.avatar),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = (profile?.display_name ?: "Me").take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile?.display_name ?: "Me",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "@${profile?.username ?: "user"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!profile?.bio.isNullOrBlank()) {
                            Text(
                                text = profile?.bio ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    }

                    IconButton(onClick = onEditProfile) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_profile))
                    }
                }
            }
        }

        // Navigation Items
        item {
            SettingsNavigationRow(
                icon = Icons.Default.AccountCircle,
                title = stringResource(R.string.usernames_title),
                subtitle = profile?.username?.let { "@$it" }
                    ?: stringResource(R.string.usernames_add),
                onClick = onOpenUsernames
            )
        }

        item {
            SettingsNavigationRow(
                icon = Icons.Default.Settings,
                title = "Appearance & Themes",
                subtitle = "Themes, colors, bubble text size & animations",
                onClick = { onOpenCategory(SettingsCategory.APPEARANCE) }
            )
        }

        item {
            SettingsNavigationRow(
                icon = Icons.Default.Lock,
                title = "Privacy & Security",
                subtitle = "Auto-delete, seen indicators, visibility",
                onClick = { onOpenCategory(SettingsCategory.PRIVACY) }
            )
        }

        item {
            SettingsNavigationRow(
                icon = Icons.Default.Info,
                title = "Language",
                subtitle = "App display language",
                onClick = { onOpenCategory(SettingsCategory.LANGUAGE) }
            )
        }

        item {
            SettingsNavigationRow(
                icon = Icons.Default.Build,
                title = "Developer Settings",
                subtitle = "Debug logs, auto-save & diagnostic options",
                onClick = { onOpenCategory(SettingsCategory.DEVELOPER) }
            )
        }

        item { ChatBackupSettingsSection() }

        item { UpdatesSettingsSection() }

        item {
            Surface(
                onClick = onLogout,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.log_out_2),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

/** Consistent elevated card wrapper used for grouped settings controls (switches, sliders, checkboxes). */
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppearanceCategoryScreen(onOpenAnimations: () -> Unit) {
    val settingsContext = LocalContext.current
    val themePref by SettingsManager.themeFlow.collectAsState()
    val textSize by SettingsManager.bubbleTextSizeFlow.collectAsState()
    val myBubbleColor by SettingsManager.myBubbleColorFlow.collectAsState()
    val otherBubbleColor by SettingsManager.otherBubbleColorFlow.collectAsState()
    val chatTheme by SettingsManager.chatThemeFlow.collectAsState()
    val wallpaperBrightness by SettingsManager.wallpaperBrightnessFlow.collectAsState()
    val applyToAlreadyChanged by SettingsManager.applyThemeToAlreadyChangedFlow.collectAsState()
    val accentColor by SettingsManager.accentColorFlow.collectAsState()
    val useAccentPalette by SettingsManager.useAccentPaletteFlow.collectAsState()
    val wallpaperMode by SettingsManager.wallpaperModeFlow.collectAsState()
    val wallpaperColor by SettingsManager.wallpaperColorFlow.collectAsState()
    val wallpaperImageUri by SettingsManager.wallpaperImageUriFlow.collectAsState()
    val wallpaperBlur by SettingsManager.wallpaperBlurFlow.collectAsState()
    val isDarkNow = androidx.compose.foundation.isSystemInDarkTheme()

    val wallpaperPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            // Persist read access, or the wallpaper is a broken image after the
            // next restart: the implicit grant dies with the process.
            runCatching {
                settingsContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            SettingsManager.setWallpaperImageUri(uri.toString())
            SettingsManager.setWallpaperMode("image")
        }
    }
    val bounciness by SettingsManager.bouncinessFlow.collectAsState()
    val stretchiness by SettingsManager.stretchinessFlow.collectAsState()
    val allAnimations by SettingsManager.allAnimationsFlow.collectAsState()
    val currentChatTheme = remember(chatTheme) { AvailableChatThemes.find { it.name == chatTheme } ?: AvailableChatThemes.first() }

    val colorOptions = listOf(0xFF9C27B0, 0xFF1976D2, 0xFF388E3C, 0xFFF57C00, 0xFFD32F2F, 0xFF3F51B5, 0xFF512DA8)
    val themeOptions = listOf("Default", "Vintage", "Modernism", "Love", "Ocean", "Forest")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Theme Selector
        item {
            SettingsCard {
                Text(stringResource(R.string.app_theme), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                // FlowRow, not a weighted Row: there are four options now and
                // the fourth would squeeze all of them past readability on a
                // narrow phone. Labels come from resources rather than
                // pref.name, which was rendering the raw enum — "SYSTEM" in
                // shouting caps, untranslated, in every language.
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemePreference.values().forEach { pref ->
                        FilterChip(
                            selected = themePref == pref,
                            onClick = { SettingsManager.setTheme(pref) },
                            label = {
                                Text(
                                    stringResource(
                                        when (pref) {
                                            ThemePreference.SYSTEM -> R.string.theme_system
                                            ThemePreference.LIGHT -> R.string.theme_light
                                            ThemePreference.DARK -> R.string.theme_dark
                                            ThemePreference.BLACK -> R.string.theme_black
                                        }
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }

        // Live chat preview — reflects every appearance setting below as you change it, and is
        // fully interactive (scroll it, swipe a bubble) just like the Animations screen's preview.
        item {
            Text(stringResource(R.string.live_preview), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = stringResource(R.string.updates_live_as_you_change_the),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            CombinedChatPreview(
                theme = currentChatTheme,
                textSize = textSize,
                bounciness = bounciness,
                stretchiness = stretchiness,
                allAnimations = allAnimations
            )
        }

        // Accent colour, and the palette derived from it.
        item {
            SettingsCard {
                Text(
                    stringResource(R.string.appearance_accent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.appearance_accent_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                ColorSwatchRow(
                    options = ACCENT_OPTIONS,
                    selected = accentColor,
                    onSelect = { SettingsManager.setAccentColor(it) }
                )

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.appearance_use_accent),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            stringResource(R.string.appearance_use_accent_explain),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useAccentPalette,
                        onCheckedChange = { SettingsManager.setUseAccentPalette(it) }
                    )
                }

                if (!useAccentPalette) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.appearance_my_bubble),
                        style = MaterialTheme.typography.labelMedium
                    )
                    ColorSwatchRow(
                        options = ACCENT_OPTIONS,
                        selected = myBubbleColor,
                        onSelect = { SettingsManager.setMyBubbleColor(it) }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.appearance_their_bubble),
                        style = MaterialTheme.typography.labelMedium
                    )
                    ColorSwatchRow(
                        options = ACCENT_OPTIONS,
                        selected = otherBubbleColor,
                        onSelect = { SettingsManager.setOtherBubbleColor(it) }
                    )
                }

                Spacer(Modifier.height(12.dp))
                ContrastNote(
                    accent = accentColor,
                    useAccentPalette = useAccentPalette,
                    myBubbleColor = myBubbleColor,
                    darkTheme = isDarkNow
                )
            }
        }

        // Wallpaper.
        item {
            SettingsCard {
                Text(
                    stringResource(R.string.appearance_wallpaper),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))

                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WALLPAPER_MODES.forEach { mode ->
                        FilterChip(
                            selected = wallpaperMode == mode.key,
                            onClick = {
                                if (mode.key == "image") {
                                    wallpaperPicker.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                } else {
                                    SettingsManager.setWallpaperMode(mode.key)
                                }
                            },
                            label = { Text(stringResource(mode.labelRes)) }
                        )
                    }
                }

                if (wallpaperMode == "solid" || wallpaperMode == "gradient") {
                    Spacer(Modifier.height(10.dp))
                    ColorSwatchRow(
                        options = ACCENT_OPTIONS,
                        selected = wallpaperColor,
                        onSelect = { SettingsManager.setWallpaperColor(it) }
                    )
                }

                if (wallpaperMode == "image" && wallpaperImageUri != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.appearance_wallpaper_chosen),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.appearance_dim, (100 - wallpaperBrightness * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = wallpaperBrightness,
                    onValueChange = { SettingsManager.setWallpaperBrightness(it) },
                    valueRange = 0.2f..1f
                )

                Text(
                    stringResource(R.string.appearance_blur, (wallpaperBlur * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = wallpaperBlur,
                    onValueChange = { SettingsManager.setWallpaperBlur(it) },
                    valueRange = 0f..1f
                )
            }
        }

        // Reset.
        item {
            OutlinedButton(
                onClick = {
                    SettingsManager.resetAppearance()
                    com.neveroff.ui.components.AppToast.success(
                        settingsContext.getString(R.string.appearance_reset_done)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.appearance_reset))
            }
        }

        // Text Size
        item {
            SettingsCard {
                Text("Bubble Text Size: ${textSize.toInt()} sp", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = textSize,
                    onValueChange = { SettingsManager.setBubbleTextSize(it) },
                    valueRange = 10f..30f
                )
            }
        }

        // Bubble color pickers removed per Stage 3 spec

        // Chat Theme
        item {
            val wallpaperBlocked = com.neveroff.ui.components.isFeatureRestricted(
                com.neveroff.data.remote.EnforcementState.Feature.CHANGE_WALLPAPER
            )
            SettingsCard(
                modifier = Modifier.alpha(if (wallpaperBlocked) 0.38f else 1f)
            ) {
                Text(stringResource(R.string.chat_wallpaper_theme), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.for_all_chats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(com.neveroff.ui.screens.AvailableChatThemes.size) { index ->
                        val t = com.neveroff.ui.screens.AvailableChatThemes[index]
                        val brush = if (t.backgroundGradient.size >= 2 && t.backgroundGradient[0] != Color.Transparent) {
                            androidx.compose.ui.graphics.Brush.verticalGradient(t.backgroundGradient)
                        } else {
                            androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant)
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                if (wallpaperBlocked) {
                                    com.neveroff.ui.components.AppToast.show(
                                        settingsContext.getString(
                                            R.string.restriction_blocked_toast,
                                            settingsContext.getString(R.string.feature_change_wallpaper)
                                        )
                                    )
                                } else SettingsManager.setChatTheme(t.name)
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp, 80.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(brush)
                                    .border(
                                        width = if (chatTheme == t.name) 2.dp else 0.dp,
                                        color = if (chatTheme == t.name) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                            ) {
                                Box(modifier = Modifier.padding(4.dp).align(Alignment.CenterStart).size(30.dp, 12.dp).clip(RoundedCornerShape(4.dp)).background(t.otherBubbleColor))
                                Box(modifier = Modifier.padding(4.dp).align(Alignment.CenterEnd).size(30.dp, 12.dp).clip(RoundedCornerShape(4.dp)).background(t.myBubbleColor))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(t.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Wallpaper Brightness
        item {
            SettingsCard {
                Text("Wallpaper Brightness: ${(wallpaperBrightness * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = wallpaperBrightness,
                    onValueChange = { SettingsManager.setWallpaperBrightness(it) },
                    valueRange = 0.2f..1.0f
                )
            }
        }

        // Apply theme to already changed chats
        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.apply_to_already_changed_chats), style = MaterialTheme.typography.bodyLarge)
                    Checkbox(
                        checked = applyToAlreadyChanged,
                        onCheckedChange = { SettingsManager.setApplyThemeToAlreadyChanged(it) }
                    )
                }
            }
        }

        // Animations Menu Link — moved to the bottom now that its old spot holds the live preview
        item {
            Surface(
                onClick = onOpenAnimations,
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.animations), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun AnimationsCategoryScreen() {
    val bounciness by SettingsManager.bouncinessFlow.collectAsState()
    val stretchiness by SettingsManager.stretchinessFlow.collectAsState()
    val allAnimations by SettingsManager.allAnimationsFlow.collectAsState()
    val optimiseOptimisation by SettingsManager.optimiseOptimisationFlow.collectAsState()
    val chatThemeName by SettingsManager.chatThemeFlow.collectAsState()
    val textSize by SettingsManager.bubbleTextSizeFlow.collectAsState()
    val currentTheme = remember(chatThemeName) { AvailableChatThemes.find { it.name == chatThemeName } ?: AvailableChatThemes.first() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Live preview gallery: the exact same MessageBubble used in a real chat, played back
        // in slow motion. Swipe right for dedicated single-purpose previews (scroll only,
        // send/receive only, reply gesture only) — every window is interactive.
        item {
            Text(stringResource(R.string.live_preview), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = stringResource(R.string.same_bubbles_and_physics_as_a),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            ChatAnimationPreviewGallery(
                theme = currentTheme,
                textSize = textSize,
                bounciness = bounciness,
                stretchiness = stretchiness,
                allAnimations = allAnimations
            )
        }

        item {
            SettingsCard {
                Text(stringResource(R.string.animation_smoothness), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                val currentPreset = remember(allAnimations, bounciness) { AnimationSmoothness.resolve(allAnimations, bounciness) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimationSmoothness.values().forEach { preset ->
                        val selected = preset == currentPreset
                        Surface(
                            onClick = {
                                SettingsManager.setAllAnimations(preset.allAnimations)
                                if (preset.allAnimations) SettingsManager.setBounciness(preset.bounciness)
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.bubble_stretchiness), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = stretchiness,
                        onCheckedChange = { SettingsManager.setStretchiness(it) },
                        enabled = allAnimations
                    )
                }
            }
        }

        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.optimise_optimisation), style = MaterialTheme.typography.bodyLarge)
                    Checkbox(
                        checked = optimiseOptimisation,
                        onCheckedChange = { SettingsManager.setOptimiseOptimisation(it) }
                    )
                }
            }
        }
    }
}

/** "Off", "1 hour", "6 hours", "1 day" — built from resources so it translates. */
@Composable
private fun autoDeleteLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.auto_delete_off)
    minutes < 60 -> stringResource(R.string.auto_delete_minutes, minutes)
    minutes < 24 * 60 -> stringResource(R.string.auto_delete_hours, minutes / 60)
    else -> stringResource(R.string.auto_delete_days_n, minutes / (24 * 60))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PrivacyCategoryScreen() {
    val autoDeleteDays by SettingsManager.autoDeleteDaysFlow.collectAsState()
    val seenSelf by SettingsManager.seenIndicatorSelfFlow.collectAsState()
    val seenOthers by SettingsManager.seenIndicatorOthersFlow.collectAsState()
    val visBio by SettingsManager.visibilityBioFlow.collectAsState()
    val visAvatar by SettingsManager.visibilityAvatarFlow.collectAsState()
    val visOnline by SettingsManager.visibilityOnlineFlow.collectAsState()

    val options = listOf("Everyone", "Contacts", "Nobody")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SettingsCard {
                Text(
                    stringResource(R.string.auto_delete_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.auto_delete_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                // Discrete choices rather than a slider. The useful values are
                // not evenly spaced — an hour and a month both matter, and
                // nobody wants 17 days — and a slider could not reach below a
                // day at all, which is what people were asking for.
                val autoDeleteMinutes by SettingsManager.autoDeleteMinutesFlow.collectAsState()
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsManager.AUTO_DELETE_CHOICES.forEach { minutes ->
                        val selected = autoDeleteMinutes == minutes
                        FilterChip(
                            selected = selected,
                            onClick = { SettingsManager.setAutoDeleteMinutes(minutes) },
                            label = { Text(autoDeleteLabel(minutes)) }
                        )
                    }
                }
            }
        }

        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.send_read_receipts_seen), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = seenSelf,
                        onCheckedChange = { SettingsManager.setSeenIndicatorSelf(it) }
                    )
                }
            }
        }

        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.show_partner_read_receipts), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = seenOthers,
                        onCheckedChange = { SettingsManager.setSeenIndicatorOthers(it) }
                    )
                }
            }
        }

        item {
            SettingsCard {
                Text(stringResource(R.string.who_can_see_my_avatar), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = visAvatar == opt,
                            onClick = { SettingsManager.setVisibilityAvatar(opt) },
                            label = { Text(opt) }
                        )
                    }
                }
            }
        }

        item {
            SettingsCard {
                Text(stringResource(R.string.who_can_see_my_bio), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = visBio == opt,
                            onClick = { SettingsManager.setVisibilityBio(opt) },
                            label = { Text(opt) }
                        )
                    }
                }
            }
        }

        item {
            SettingsCard {
                Text(stringResource(R.string.who_can_see_my_online_status), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = visOnline == opt,
                            onClick = { SettingsManager.setVisibilityOnline(opt) },
                            label = { Text(opt) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageCategoryScreen() {
    val language by SettingsManager.languageFlow.collectAsState()
    var search by remember { mutableStateOf("") }
    val languages = listOf("English" to "en", "Русский" to "ru")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text(stringResource(R.string.search_language)) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        languages.filter { it.first.contains(search, ignoreCase = true) }.forEach { (name, code) ->
            Surface(
                onClick = { SettingsManager.setLanguage(code) },
                shape = RoundedCornerShape(12.dp),
                color = if (language == code) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = name, style = MaterialTheme.typography.titleMedium)
                    if (language == code) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.selected), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeveloperCategoryScreen() {
    val debugEnabled by SettingsManager.debugPanelFlow.collectAsState()
    val autoSaveLogs by SettingsManager.autoSaveLogsFlow.collectAsState()
    val logPeriod by SettingsManager.logPeriodFlow.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.enable_debug_panel), style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = debugEnabled,
                        onCheckedChange = { SettingsManager.setDebugPanelEnabled(it) }
                    )
                }
            }
        }

        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.auto_save_diagnostic_logs), style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = autoSaveLogs,
                        onCheckedChange = { SettingsManager.setAutoSaveLogs(it) }
                    )
                }
            }
        }

        item {
            SettingsCard {
                Text("Log Rotation Period: $logPeriod", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1h", "6h", "12h", "24h").forEach { period ->
                        FilterChip(
                            selected = logPeriod == period,
                            onClick = { SettingsManager.setLogPeriod(period) },
                            label = { Text(period) }
                        )
                    }
                }
            }
        }
    }
}
/**
 * Updates block in the main settings menu.
 *
 * Deliberately always present: a user who dismissed the update banner on the chat list still
 * needs somewhere to find the update, so this is the durable entry point for it. Also carries
 * the auto-download toggle and mirrors any in-flight download.
 */
@Composable
private fun UpdatesSettingsSection() {
    val context = LocalContext.current
    val autoDownload by SettingsManager.autoDownloadUpdatesFlow.collectAsState()
    val available by com.neveroff.util.UpdateController.available.collectAsState()
    val progress by com.neveroff.util.UpdateController.progress.collectAsState()
    val downloaded by com.neveroff.util.UpdateController.downloadedFile.collectAsState()
    var showWhatsNew by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.updates),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Current version ${com.neveroff.util.UpdateManager.CURRENT_APP_VERSION}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Manual check. Autodownload only runs on the app's own schedule, so
            // without this there is no way to ask "is there an update?" and get
            // an answer — and "nothing happened" is indistinguishable from
            // "the check failed".
            var checking by remember { mutableStateOf(false) }
            var checkResult by remember { mutableStateOf<String?>(null) }
            val checkScope = rememberCoroutineScope()

            OutlinedButton(
                onClick = {
                    checking = true
                    checkResult = null
                    checkScope.launch {
                        try {
                            com.neveroff.util.UpdateController.refresh()
                            checkResult = if (com.neveroff.util.UpdateController.available.value != null) {
                                null // the version card below says it better than a line of text
                            } else {
                                "You're on the latest version."
                            }
                        } catch (e: Exception) {
                            checkResult = "Couldn't check right now. Try again when you have a connection."
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.checking))
                } else {
                    Text(stringResource(R.string.check_for_updates))
                }
            }

            checkResult?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.autodownload_updates), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(R.string.download_new_versions_automatically_while_you),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoDownload,
                    onCheckedChange = { enabled ->
                        SettingsManager.setAutoDownloadUpdates(enabled)
                        // Turning it on with an update already waiting should act immediately
                        // rather than only from the next check.
                        if (enabled) com.neveroff.util.UpdateController.maybeAutoDownload(context)
                    }
                )
            }

            if (available != null) {
                val version = available!!
                Spacer(Modifier.height(14.dp))
                Divider()
                Spacer(Modifier.height(14.dp))

                Text(
                    text = "Version ${version.version} available",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (progress != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Downloading ${((progress ?: 0f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { com.neveroff.util.UpdateController.cancelDownload() }) {
                            Text(stringResource(R.string.stop))
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!version.description.isBlank()) {
                            TextButton(onClick = { showWhatsNew = true }) { Text(stringResource(R.string.what_s_new)) }
                        }
                        Spacer(Modifier.weight(1f))
                        if (downloaded != null) {
                            Button(onClick = {
                                com.neveroff.util.UpdateManager.installApk(context, downloaded!!)
                            }) { Text(stringResource(R.string.install)) }
                        } else {
                            Button(onClick = {
                                com.neveroff.util.UpdateController.startDownload(context)
                            }) { Text(stringResource(R.string.download)) }
                        }
                    }
                }
            }
        }
    }

    if (showWhatsNew && available != null) {
        com.neveroff.ui.components.WhatsNewDialog(
            version = available!!.version,
            description = available!!.description,
            onDismiss = { showWhatsNew = false }
        )
    }
}

/** Wallpaper sources offered on the appearance screen. */
private data class WallpaperMode(val key: String, val labelRes: Int)

private val WALLPAPER_MODES = listOf(
    WallpaperMode("theme", R.string.appearance_wallpaper_theme),
    WallpaperMode("solid", R.string.appearance_wallpaper_solid),
    WallpaperMode("gradient", R.string.appearance_wallpaper_gradient),
    WallpaperMode("image", R.string.appearance_wallpaper_image)
)

/** Shared palette for accent, bubble and wallpaper pickers. */
private val ACCENT_OPTIONS = listOf(
    0xFF7C4DFFL, 0xFF9C27B0L, 0xFF1976D2L, 0xFF00ACC1L,
    0xFF388E3CL, 0xFFF57C00L, 0xFFD32F2FL, 0xFF546E7AL,
    0xFF121212L, 0xFFECEFF1L
)

@Composable
private fun ColorSwatchRow(
    options: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        options.forEach { value ->
            val color = Color(value)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (value == selected) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            // A hairline so near-background swatches stay visible.
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        }
                    )
                    .clickable { onSelect(value) }
            )
        }
    }
}

/**
 * Reports the contrast the current choice actually achieves.
 *
 * Text colour is computed from the bubble rather than stored, so it can never
 * be saved unreadable — but a user who picks a colour we then have to correct
 * deserves to be told, rather than wondering why their pale yellow came out
 * darker than the swatch.
 */
@Composable
private fun ContrastNote(
    accent: Long,
    useAccentPalette: Boolean,
    myBubbleColor: Long,
    darkTheme: Boolean
) {
    val chosen = Color(if (useAccentPalette) accent else myBubbleColor)
    val corrected = com.neveroff.ui.theme.ChatAppearance.ensureReadable(chosen)
    val textColor = com.neveroff.ui.theme.ChatAppearance.textColorOn(corrected)
    val ratio = com.neveroff.ui.theme.ChatAppearance.contrastRatio(corrected, textColor)
    val wasAdjusted = corrected != chosen

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(corrected),
            contentAlignment = Alignment.Center
        ) {
            Text("Aa", color = textColor, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (wasAdjusted) {
                stringResource(R.string.appearance_contrast_adjusted, String.format("%.1f", ratio))
            } else {
                stringResource(R.string.appearance_contrast_ok, String.format("%.1f", ratio))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
