package com.neveroff

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.neveroff.ui.theme.NeverOffTheme
import com.neveroff.ui.screens.*
import com.neveroff.data.remote.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.realtime.realtime
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import com.neveroff.util.SettingsManager
import com.neveroff.data.local.AppDatabase
import com.neveroff.util.ThemePreference
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeRecord
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.messaging.FirebaseMessaging
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : FragmentActivity() {
    private val intentState = mutableStateOf<android.content.Intent?>(null)
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            com.neveroff.ui.components.AppToast.show("Notifications disabled")
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.neveroff.crypto.CryptoManager.init(applicationContext)
        SettingsManager.init(applicationContext)
        com.neveroff.util.LoggerManager.init(applicationContext)
        com.neveroff.util.LoggerManager.setupGlobalExceptionHandler()
        intentState.value = intent

        setContent {
            val currentLanguage by SettingsManager.languageFlow.collectAsState()
            val currentThemePref by SettingsManager.themeFlow.collectAsState()

            val locale = java.util.Locale(currentLanguage)
            val configuration = android.content.res.Configuration(LocalConfiguration.current)
            configuration.setLocale(locale)
            
            val context = LocalContext.current
            val localizedContext = context.createConfigurationContext(configuration)

            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides localizedContext,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                val isDark = when (currentThemePref) {
                    ThemePreference.SYSTEM -> isSystemInDarkTheme()
                    ThemePreference.LIGHT -> false
                    ThemePreference.DARK -> true
                }

                NeverOffTheme(darkTheme = isDark) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val currentIntent by intentState
                            AppNavigation(currentIntent)
                            com.neveroff.ui.components.AppToastHost()
                        }
                    }
                }
            }
        }
        
        askNotificationPermission()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Serializable
data class ChatInsert(
    val id: String = java.util.UUID.randomUUID().toString(),
    val is_group: Boolean = false
)
@Serializable
data class ChatResponse(val id: String)
@Serializable
data class ChatParticipantInsert(val chat_id: String, val user_id: String)

/**
 * Rebuilds the locally cached profile, or null when this device has never completed
 * profile setup. Requires both a cached profile id and username: "my_user_id" on its own
 * only proves the account is authenticated, and treating that as a profile is what used to
 * drop freshly registered users onto chat_list with an empty "Me" profile.
 */
private fun loadCachedProfile(prefs: android.content.SharedPreferences): com.neveroff.ui.screens.Profile? {
    val cachedId = com.neveroff.util.SecurePrefs.getString(prefs, "profile_id")
    val cachedUsername = com.neveroff.util.SecurePrefs.getString(prefs, "profile_username")
    if (cachedId.isNullOrEmpty() || cachedUsername.isNullOrBlank()) return null

    val cachedDisplayName = com.neveroff.util.SecurePrefs.getString(prefs, "profile_display_name")
    return com.neveroff.ui.screens.Profile(
        id = cachedId,
        username = cachedUsername,
        display_name = if (!cachedDisplayName.isNullOrBlank()) cachedDisplayName else cachedUsername,
        bio = com.neveroff.util.SecurePrefs.getString(prefs, "profile_bio"),
        public_key = com.neveroff.util.SecurePrefs.getString(prefs, "profile_public_key") ?: ""
    )
}

@Composable
fun AppNavigation(intent: android.content.Intent?) {
    val navController = rememberNavController()
    var sessionStatus by remember { mutableStateOf<SessionStatus?>(null) }
    var userEmail by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var myProfile by remember { mutableStateOf<com.neveroff.ui.screens.Profile?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }

    // One repository for the whole graph: channel screens, the chat list and the
    // channel cards inside chats all read from the same local mirror.
    val myUserId = com.neveroff.util.CurrentUser.id(context)
    val channelRepository = remember(myUserId) {
        com.neveroff.data.repository.ChannelRepository(db, myUserId)
    }
    
    // Track clicked chat from intent
    var clickedChatId by remember { mutableStateOf<String?>(null) }
    var clickedChannelId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(intent) {
        val cid = intent?.getStringExtra("chat_id")
        if (!cid.isNullOrEmpty()) {
            clickedChatId = cid
        }
        val chanId = intent?.getStringExtra("channel_id")
        if (!chanId.isNullOrEmpty()) {
            clickedChannelId = chanId
        }
    }

    // The realtime socket has no automatic "reconnect on app resume" anywhere — if it dies
    // while the app is backgrounded (network change, OS suspending the connection, etc.),
    // nothing brings it back until something happens to call connect() again. Per-chat message
    // delivery would then silently stop working until the chat screen (and its subscription)
    // gets fully torn down and recreated, i.e. exactly "reopen the app to see new messages".
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && sessionStatus is SessionStatus.Authenticated) {
                scope.launch {
                    if (SupabaseClient.client.realtime.status.value != Realtime.Status.CONNECTED) {
                        try {
                            SupabaseClient.client.realtime.connect()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(sessionStatus, clickedChatId) {
        if (sessionStatus is SessionStatus.Authenticated && clickedChatId != null) {
            val cid = clickedChatId!!
            clickedChatId = null
            intent?.removeExtra("chat_id")
            
            scope.launch {
                try {
                    val chat = db.chatDao().getChatById(cid)
                    if (chat != null) {
                        val encodedPubKey = android.net.Uri.encode(chat.otherPubKey, "").ifEmpty { "empty" }
                        val encodedName = android.net.Uri.encode(chat.otherUserName, "").ifEmpty { "empty" }
                        try {
                            navController.navigate("chat/${chat.id}/$encodedPubKey/$encodedName")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    LaunchedEffect(sessionStatus, clickedChannelId) {
        val chanId = clickedChannelId
        if (chanId != null && (sessionStatus is SessionStatus.Authenticated || myProfile != null)) {
            clickedChannelId = null
            intent?.removeExtra("channel_id")
            navController.navigate("channel/$chanId")
        }
    }

    val prefs = remember { context.getSharedPreferences("neveroff_prefs", android.content.Context.MODE_PRIVATE) }
    var isRealtimeConnected by remember { mutableStateOf(false) }

    // FAST LOCAL-FIRST RESOLUTION: Load local profile and navigate away from loading instantly.
    // Only a cached profile that was actually registered (id + username) counts - "my_user_id"
    // alone just means the account is authenticated and may still have no profile row.
    LaunchedEffect(Unit) {
        val localProf = loadCachedProfile(prefs)
        if (localProf != null) {
            myProfile = localProf
            com.neveroff.data.cache.ProfileCache.put(localProf)

            if (navController.currentDestination?.route in listOf(null, "loading")) {
                navController.navigate("chat_list") { popUpTo(0) }
            }
        }
    }
    
    LaunchedEffect(Unit) {
        SupabaseClient.client.auth.sessionStatus.collect { status ->
            sessionStatus = status
            if (status is SessionStatus.Authenticated) {
                val currentUserId = status.session.user?.id ?: SupabaseClient.client.auth.currentUserOrNull()?.id
                if (currentUserId != null) {
                    com.neveroff.util.SecurePrefs.putString(prefs, "my_user_id", currentUserId)
                }

                // Connect to Realtime WebSocket once per session
                if (!isRealtimeConnected) {
                    isRealtimeConnected = true
                    launch(Dispatchers.IO) {
                        try {
                            com.neveroff.util.DebugLogger.log("Connecting Realtime...")
                            SupabaseClient.client.realtime.connect()
                            com.neveroff.util.DebugLogger.log("Realtime connect() called")
                        } catch (e: Exception) {
                            com.neveroff.util.DebugLogger.log("Realtime connect failed: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }

                val userId = currentUserId ?: status.session.user?.id
                if (userId != null) {
                    // 1. FAST PATH: Ensure local profile is set. A freshly verified account has no
                    // cached profile yet - do NOT synthesise one from the user id, or registration
                    // silently lands on chat_list with an empty "Me" profile instead of profile_setup.
                    if (myProfile == null) {
                        val localProf = loadCachedProfile(prefs)
                        if (localProf != null) {
                            myProfile = localProf
                            com.neveroff.data.cache.ProfileCache.put(localProf)
                        }
                    }

                    // Navigate immediately if currently on loading/auth screen and local profile/session is present
                    if (myProfile != null && navController.currentDestination?.route in listOf(null, "loading", "login", "verify")) {
                        navController.navigate("chat_list") { popUpTo(0) }
                    }

                    // 2. BACKGROUND SYNC: Fetch latest profile from Supabase
                    launch(Dispatchers.IO) {
                        var remoteProfile: com.neveroff.ui.screens.Profile? = null
                        var fetchSuccess = false
                        for (i in 1..2) {
                            try {
                                remoteProfile = SupabaseClient.client.postgrest["profiles"]
                                    .select { filter { eq("id", userId) } }
                                    .decodeList<com.neveroff.ui.screens.Profile>()
                                    .firstOrNull()
                                fetchSuccess = true
                                break
                            } catch (e: Exception) {
                                e.printStackTrace()
                                kotlinx.coroutines.delay(500)
                            }
                        }

                        if (fetchSuccess && remoteProfile != null) {
                            val localPublicKey = try {
                                com.neveroff.crypto.CryptoManager.getPublicKeyBase64()
                            } catch (e: Exception) { null }

                            if (localPublicKey != null && localPublicKey != remoteProfile.public_key) {
                                try {
                                    SupabaseClient.client.postgrest["profiles"]
                                        .update({ set("public_key", localPublicKey) }) {
                                            filter { eq("id", userId) }
                                        }
                                    remoteProfile = remoteProfile.copy(public_key = localPublicKey)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            
                            // Firebase token sync
                            launch {
                                try {
                                    val token = kotlinx.coroutines.withTimeoutOrNull(5000) {
                                        FirebaseMessaging.getInstance().token.await()
                                    }
                                    if (!token.isNullOrEmpty()) {
                                        SupabaseClient.client.postgrest["profiles"]
                                            .update({ set("fcm_token", token) }) {
                                                filter { eq("id", userId) }
                                            }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            withContext(Dispatchers.Main) {
                                myProfile = remoteProfile
                                remoteProfile?.let { prof ->
                                    com.neveroff.data.cache.ProfileCache.put(prof)
                                    com.neveroff.util.SecurePrefs.putString(prefs, "profile_id", prof.id)
                                    com.neveroff.util.SecurePrefs.putString(prefs, "profile_username", prof.username)
                                    com.neveroff.util.SecurePrefs.putString(prefs, "profile_display_name", prof.display_name)
                                    com.neveroff.util.SecurePrefs.putString(prefs, "profile_bio", prof.bio)
                                    com.neveroff.util.SecurePrefs.putString(prefs, "profile_public_key", prof.public_key)
                                }
                                    
                                if (navController.currentDestination?.route in listOf("loading", "login", "verify")) {
                                    navController.navigate("chat_list") { popUpTo(0) }
                                }
                            }
                        } else if (fetchSuccess && remoteProfile == null) {
                            // User authenticated but profile record missing on server
                            withContext(Dispatchers.Main) {
                                if (myProfile == null && navController.currentDestination?.route in listOf("loading", "login", "verify")) {
                                    navController.navigate("profile_setup") { popUpTo(0) }
                                }
                            }
                        } else {
                            // Network fetch failed (e.g. offline)
                            withContext(Dispatchers.Main) {
                                if (navController.currentDestination?.route in listOf("loading", "login", "verify")) {
                                    if (myProfile != null) {
                                        // Navigate to chat_list using local data
                                        navController.navigate("chat_list") { popUpTo(0) }
                                    } else {
                                        // Nothing cached: we can't tell whether this account has a
                                        // profile yet, so send the user back instead of stranding
                                        // them on a spinner or on an empty "Me" profile.
                                        com.neveroff.ui.components.AppToast.error(
                                            "Couldn't reach the server. Check your connection and try again."
                                        )
                                        navController.navigate("login") { popUpTo(0) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (navController.currentDestination?.route in listOf("loading", "login", "verify")) {
                        navController.navigate("profile_setup") { popUpTo(0) }
                    }
                }
            } else if (status is SessionStatus.NotAuthenticated) {
                isRealtimeConnected = false
                val cachedProfile = loadCachedProfile(prefs)
                if (cachedProfile == null && navController.currentDestination?.route in listOf("loading", "profile_setup")) {
                    navController.navigate("login") { popUpTo(0) }
                } else if (cachedProfile != null && navController.currentDestination?.route == "loading") {
                    // If offline, still allow opening chat_list with cached profile
                    navController.navigate("chat_list") { popUpTo(0) }
                }
            }
        }
    }
    
    LaunchedEffect(sessionStatus) {
        if (sessionStatus is SessionStatus.Authenticated) {
            com.neveroff.data.remote.PresenceManager.startTracking()
            com.neveroff.data.remote.GlobalRealtimeManager.start(context)
        } else if (sessionStatus is SessionStatus.NotAuthenticated) {
            com.neveroff.data.remote.PresenceManager.stopTracking()
            com.neveroff.data.remote.GlobalRealtimeManager.stop()
        }
    }

    NavHost(
        navController = navController,
        startDestination = "loading",
        enterTransition = { fadeIn(animationSpec = tween(300)) },
        exitTransition = { fadeOut(animationSpec = tween(300)) },
        popEnterTransition = { fadeIn(animationSpec = tween(300)) },
        popExitTransition = { fadeOut(animationSpec = tween(300)) }
    ) {
        composable("loading") {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        composable("profile_setup") {
            ProfileSetupScreen(onComplete = {
                // The session is already Authenticated, so the status collector won't emit
                // again - resolve the freshly written profile here instead of bouncing off
                // "loading", which nothing would then navigate away from.
                loadCachedProfile(prefs)?.let { prof ->
                    myProfile = prof
                    com.neveroff.data.cache.ProfileCache.put(prof)
                }
                navController.navigate("chat_list") { popUpTo(0) }
            })
        }
        composable("login") {
            LoginScreen(onCodeSent = { email ->
                userEmail = email
                navController.navigate("verify")
            })
        }
        composable("verify") {
            VerifyOtpScreen(email = userEmail, onVerified = {
                val userId = SupabaseClient.client.auth.currentUserOrNull()?.id
                if (userId != null) {
                    com.neveroff.util.SecurePrefs.putString(prefs, "my_user_id", userId)
                }
                // The session collector routes to chat_list/profile_setup on its own; only
                // fall back to the spinner if it hasn't moved us off "verify" yet, otherwise
                // we'd bounce the user back out of the destination it just picked.
                if (navController.currentDestination?.route == "verify") {
                    navController.navigate("loading") { popUpTo(0) }
                }
            })
        }
        composable("chat_list") {
            com.neveroff.data.remote.GlobalRealtimeManager.currentActiveChatId = null
            ChatListScreen(
                currentProfile = myProfile,
                onChatSelected = { chatId, pubKey, name ->
                    val encodedPubKey = android.net.Uri.encode(pubKey, "").ifEmpty { "empty" }
                    val encodedName = android.net.Uri.encode(name, "").ifEmpty { "empty" }
                    try {
                        navController.navigate("chat/$chatId/$encodedPubKey/$encodedName")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
                onSearchUsers = {
                    navController.navigate("search_users")
                },
                onMyProfileClick = {
                    navController.navigate("user_profile")
                },
                onProfileUpdated = { updated ->
                    myProfile = updated
                },
                onChannelSelected = { channelId ->
                    navController.navigate("channel/$channelId")
                }
            )
        }
        composable("user_profile") {
            var showEditProfile by remember { mutableStateOf(false) }
            var pendingAvatarUri by remember { mutableStateOf<android.net.Uri?>(null) }
            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                if (uri != null) pendingAvatarUri = uri
            }

            if (pendingAvatarUri != null) {
                com.neveroff.ui.components.ImageEditorDialog(
                    imageUri = pendingAvatarUri!!,
                    circularCrop = true,
                    onCancel = { pendingAvatarUri = null },
                    onConfirm = { bitmap ->
                        pendingAvatarUri = null
                        scope.launch {
                            try {
                                val myId = myProfile?.id ?: return@launch
                                com.neveroff.ui.components.AppToast.show("Начинаем загрузку...")
                                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.neveroff.util.ImageHelper.compressBitmap(bitmap)
                                }
                                val fileName = "${java.util.UUID.randomUUID()}.jpg"
                                val bucket = SupabaseClient.client.storage["avatars"]
                                bucket.upload(fileName, bytes, upsert = true)
                                val publicUrl = bucket.publicUrl(fileName)

                                SupabaseClient.client.postgrest["profiles"].update(
                                    { set("avatar_url", publicUrl as String) }
                                ) {
                                    filter { eq("id", myId) }
                                }

                                myProfile = myProfile?.copy(avatar_url = publicUrl as String)
                                com.neveroff.ui.components.AppToast.success("Аватар обновлен!")
                            } catch (e: Exception) {
                                e.printStackTrace()
                                com.neveroff.ui.components.AppToast.error("Ошибка загрузки: ${e.message}")
                            }
                        }
                    }
                )
            }

            com.neveroff.ui.screens.UserProfileScreen(
                profile = myProfile,
                onBack = { navController.popBackStack() },
                onSettingsClick = { navController.navigate("settings") },
                onEditClick = { showEditProfile = true }
            )

            com.neveroff.ui.screens.EditProfileBottomSheet(
                isVisible = showEditProfile,
                profile = myProfile,
                onDismiss = { showEditProfile = false },
                onSave = { newName, newBio ->
                    scope.launch {
                        try {
                            val myId = myProfile?.id ?: return@launch
                            SupabaseClient.client.postgrest["profiles"].update(
                                { 
                                    set("display_name", newName)
                                    set("bio", newBio)
                                }
                            ) {
                                filter { eq("id", myId) }
                            }
                            myProfile = myProfile?.copy(display_name = newName, bio = newBio)
                            showEditProfile = false
                            
                            val prefs = context.getSharedPreferences("neveroff_prefs", android.content.Context.MODE_PRIVATE)
                            com.neveroff.util.SecurePrefs.putString(prefs, "profile_display_name", newName)
                            com.neveroff.util.SecurePrefs.putString(prefs, "profile_bio", newBio)

                            com.neveroff.ui.components.AppToast.success("Профиль обновлен")
                        } catch (e: Exception) {
                            e.printStackTrace()
                            com.neveroff.ui.components.AppToast.error("Ошибка сохранения: ${e.message}")
                        }
                    }
                },
                onPickAvatar = {
                    launcher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                currentProfile = myProfile,
                onBack = { navController.popBackStack() },
                onProfileUpdated = { updated ->
                    myProfile = updated
                    val prefs = context.getSharedPreferences("neveroff_prefs", android.content.Context.MODE_PRIVATE)
                    com.neveroff.util.SecurePrefs.putString(prefs, "profile_username", updated.username)
                    com.neveroff.util.SecurePrefs.putString(prefs, "profile_display_name", updated.display_name)
                    com.neveroff.util.SecurePrefs.putString(prefs, "profile_bio", updated.bio)
                },
                onLogout = {
                    scope.launch {
                        try {
                            SupabaseClient.client.auth.signOut()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        try {
                            context.getSharedPreferences("neveroff_prefs", android.content.Context.MODE_PRIVATE)
                                .edit().clear().apply()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        try {
                            com.neveroff.crypto.CryptoManager.resetKeys(context)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        try {
                            AppDatabase.resetInstance(context)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        myProfile = null
                        navController.navigate("login") { popUpTo(0) { inclusive = true } }
                    }
                }
            )
        }
        composable("search_users") {
            SearchUserScreen(
                onBack = { navController.popBackStack() },
                onCreateChannel = { navController.navigate("create_channel") },
                onChannelSelected = { channelId -> navController.navigate("channel/$channelId") },
                onUserSelected = { profile ->
                    scope.launch {
                        try {
                            val myId = SupabaseClient.client.auth.currentUserOrNull()?.id ?: return@launch
                            
                            val myParticipants = SupabaseClient.client.postgrest["chat_participants"]
                                .select { filter { eq("user_id", myId) } }
                                .decodeList<ChatParticipantInsert>()
                            val myChatIds = myParticipants.map { it.chat_id }.toSet()
                            
                            val theirParticipants = SupabaseClient.client.postgrest["chat_participants"]
                                .select { filter { eq("user_id", profile.id) } }
                                .decodeList<ChatParticipantInsert>()
                            val theirChatIds = theirParticipants.map { it.chat_id }.toSet()
                            
                            val existingChatId = myChatIds.intersect(theirChatIds).firstOrNull()
                            
                            val finalChatId = if (existingChatId != null) {
                                existingChatId
                            } else {
                                val newChatId = java.util.UUID.randomUUID().toString()
                                SupabaseClient.client.postgrest["chats"].insert(ChatInsert(id = newChatId))
                                SupabaseClient.client.postgrest["chat_participants"].insert(
                                    listOf(ChatParticipantInsert(newChatId, myId), ChatParticipantInsert(newChatId, profile.id))
                                )
                                newChatId
                            }
                            
                            var targetPubKey = profile.public_key
                            if (targetPubKey.isBlank()) {
                                val fullProfile = com.neveroff.data.cache.ProfileCache.getProfile(profile.id)
                                if (fullProfile != null) {
                                    targetPubKey = fullProfile.public_key
                                }
                            }
                            
                            val encodedPubKey = android.net.Uri.encode(targetPubKey, "").ifEmpty { "empty" }
                            val encodedName = android.net.Uri.encode(profile.display_name, "").ifEmpty { "empty" }
                            try {
                                navController.navigate("chat/$finalChatId/$encodedPubKey/$encodedName") {
                                    popUpTo("chat_list")
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            )
        }
        composable(
            route = "chat/{chatId}/{pubKey}/{userName}",
            enterTransition = { slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }, animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth }, animationSpec = tween(300)) },
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("pubKey") { type = NavType.StringType },
                navArgument("userName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
            val rawPubKeyArg = backStackEntry.arguments?.getString("pubKey") ?: ""
            val decodedPubKey = android.net.Uri.decode(rawPubKeyArg)
            val pubKey = if (decodedPubKey == "empty") "" else decodedPubKey
            val rawUserNameArg = backStackEntry.arguments?.getString("userName") ?: ""
            val decodedUserName = android.net.Uri.decode(rawUserNameArg)
            val userName = if (decodedUserName == "empty") "" else decodedUserName
            
            com.neveroff.data.remote.GlobalRealtimeManager.currentActiveChatId = chatId
            
            ChatScreen(
                chatId = chatId,
                remotePublicKey = pubKey,
                remoteUserName = userName,
                onBack = {
                    com.neveroff.data.remote.GlobalRealtimeManager.currentActiveChatId = null
                    navController.popBackStack()
                },
                onOpenChannel = { channelId ->
                    com.neveroff.data.remote.GlobalRealtimeManager.currentActiveChatId = null
                    navController.navigate("channel/$channelId")
                }
            )
        }

        composable(
            route = "create_channel",
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) {
            com.neveroff.ui.screens.CreateChannelScreen(
                repository = channelRepository,
                onBack = { navController.popBackStack() },
                onCreated = { channelId ->
                    // Land in the new channel, not back on the search screen:
                    // the next thing an owner wants is to write the first post.
                    navController.navigate("channel/$channelId") {
                        popUpTo("chat_list")
                    }
                }
            )
        }

        composable(
            route = "channel/{channelId}",
            arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString("channelId") ?: ""
            com.neveroff.ui.screens.ChannelScreen(
                channelId = channelId,
                repository = channelRepository,
                myId = myUserId,
                onBack = { navController.popBackStack() },
                onOpenProfile = { id -> navController.navigate("channel_profile/$id") },
                onOpenSettings = { id -> navController.navigate("channel_settings/$id") }
            )
        }

        composable(
            route = "channel_profile/{channelId}",
            arguments = listOf(navArgument("channelId") { type = NavType.StringType })
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString("channelId") ?: ""
            com.neveroff.ui.screens.ChannelProfileScreen(
                channelId = channelId,
                repository = channelRepository,
                myId = myUserId,
                onBack = { navController.popBackStack() },
                onOpenSettings = { id -> navController.navigate("channel_settings/$id") },
                onOpenChannel = { id ->
                    // Opened from the channel's own header: just go back to it.
                    // Opened from a shared link: replace the profile rather than
                    // stacking a second copy of it behind the channel.
                    val previous = navController.previousBackStackEntry
                    val cameFromThisChannel = previous?.destination?.route == "channel/{channelId}" &&
                        previous.arguments?.getString("channelId") == id
                    if (cameFromThisChannel) {
                        navController.popBackStack()
                    } else {
                        navController.navigate("channel/$id") {
                            popUpTo("channel_profile/$channelId") { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(
            route = "channel_settings/{channelId}",
            arguments = listOf(navArgument("channelId") { type = NavType.StringType })
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString("channelId") ?: ""
            com.neveroff.ui.screens.ChannelSettingsScreen(
                channelId = channelId,
                repository = channelRepository,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
