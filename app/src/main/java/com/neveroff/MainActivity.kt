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
import androidx.navigation.compose.currentBackStackEntryAsState
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
        com.neveroff.util.AppLanguage.init(applicationContext)
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
/**
 * The official news channel, suggested once at the end of registration.
 *
 * A constant rather than a setting: it is the one channel the app itself
 * vouches for, and making it configurable would mean a compromised config could
 * point new users at somebody else's channel presented as official.
 */
const val OFFICIAL_CHANNEL_USERNAME = "neveroff"

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
    val onboardingContext = androidx.compose.ui.platform.LocalContext.current

    // Onboarding is rendered instead of the navigation graph, not as a route
    // inside it. The session collectors below navigate on their own as soon as
    // auth state resolves, so an onboarding route would be raced off the screen
    // by a cached session before anyone had read a word of the terms.
    var languageChosen by remember {
        mutableStateOf(com.neveroff.util.AppLanguage.hasChosen(onboardingContext))
    }
    var consentAccepted by remember {
        mutableStateOf(com.neveroff.legal.LegalConsent.hasAccepted(onboardingContext))
    }
    var chosenLanguage by remember {
        mutableStateOf(com.neveroff.util.AppLanguage.get(onboardingContext))
    }

    if (!languageChosen) {
        com.neveroff.ui.screens.LanguagePickerScreen(
            onChosen = { language ->
                com.neveroff.util.AppLanguage.set(onboardingContext, language)
                chosenLanguage = language
                languageChosen = true
            }
        )
        return
    }

    if (!consentAccepted) {
        com.neveroff.ui.screens.LegalConsentScreen(
            language = chosenLanguage,
            onAccepted = {
                com.neveroff.legal.LegalConsent.record(onboardingContext, chosenLanguage)
                consentAccepted = true
            },
            // Reading the terms in the wrong language is a good reason to go back.
            onChangeLanguage = { languageChosen = false }
        )
        return
    }

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

    // Enforcement state for this account. Null until the first check completes.
    // The server enforces bans regardless of what this holds (RLS rejects the
    // writes); this exists so a suspended user sees the §4 explanation instead
    // of an opaque failure when they try to send something.
    var enforcement by remember {
        mutableStateOf<com.neveroff.data.remote.EnforcementRepository.Status?>(null)
    }
    var enforcementCheckToken by remember { mutableStateOf(0) }

    // Set when this device holds a key the account has moved on from, and a
    // recoverable backup exists. Drives the restore prompt. Not persisted: a
    // user who skips should be asked again next launch rather than silently
    // left with an unreadable history forever.
    var pendingKeyRestoreUserId by remember { mutableStateOf<String?>(null) }
    // Action id of a notice the user chose to continue past because it could not
    // be acknowledged on the server. Deliberately not persisted: an unconfirmed
    // notice should come back on the next launch.
    var deferredNoticeActionId by remember { mutableStateOf<String?>(null) }

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
            // Read receipts key off this: a message arriving while the app is
            // backgrounded has been delivered, not read, even if a chat screen
            // is still nominally open behind the lock screen.
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    com.neveroff.data.remote.GlobalRealtimeManager.isAppInForeground = true
                Lifecycle.Event.ON_PAUSE ->
                    com.neveroff.data.remote.GlobalRealtimeManager.isAppInForeground = false
                else -> Unit
            }

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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            com.neveroff.data.remote.GlobalRealtimeManager.isAppInForeground = false
        }
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

                    // Sign-out now keeps the identity key so that signing back
                    // into the same account can still read its history. This is
                    // the other half of that: a *different* account signing in
                    // on this device must not inherit the previous one's key or
                    // its decrypted message cache.
                    if (!com.neveroff.crypto.CryptoManager.canReuseIdentityFor(currentUserId)) {
                        runCatching { com.neveroff.crypto.CryptoManager.resetKeys(context) }
                        runCatching { AppDatabase.resetInstance(context) }
                    }
                    // Adopts a pre-existing key that has no owner recorded, so
                    // users upgrading into this build keep their history rather
                    // than losing it to a bookkeeping gap.
                    com.neveroff.crypto.CryptoManager.bindKeyPairToUser(currentUserId)

                    // The server's copy of the public key is reconciled against
                    // the local one further down this same block, so a
                    // regenerated pair is republished there rather than here.
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
                                // Publishing this device's key here is only
                                // correct when the account has no other device
                                // relying on the old one. Doing it
                                // unconditionally is how a second sign-in used
                                // to break the first device: the other side
                                // starts encrypting to the new key, and the
                                // original phone can no longer read anything
                                // that arrives — silently, with no error
                                // anywhere.
                                //
                                // A backup existing means the real key is
                                // recoverable, so restoring it is strictly
                                // better than replacing it. Offer that instead.
                                val backupExists = runCatching {
                                    com.neveroff.data.remote.KeyBackupRepository.exists(userId)
                                }.getOrDefault(false)

                                val remoteKeyIsReal = !remoteProfile.public_key.isNullOrBlank()

                                if (backupExists && remoteKeyIsReal) {
                                    withContext(Dispatchers.Main) {
                                        pendingKeyRestoreUserId = userId
                                    }
                                } else {
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

    // §4: on sign-in and on every explicit re-check, ask whether this account is
    // suspended. A null result means the check could not run (offline, or the
    // console backend is not deployed) and is treated as unrestricted — failing
    // closed here would lock people out of their own app over a network blip,
    // and the server-side RLS is what actually enforces the ban either way.
    LaunchedEffect(sessionStatus, enforcementCheckToken) {
        if (sessionStatus !is SessionStatus.Authenticated) {
            enforcement = null
            // Restrictions are per-account. Leaving them in place across a sign
            // out would apply one person's penalties to whoever signs in next.
            com.neveroff.data.remote.EnforcementState.reset()
            // "banned" renders a spinner while enforcement is null and has no
            // other way to be refilled, so leaving someone there after the
            // session dropped strands them on an empty screen with no way out.
            if (navController.currentDestination?.route == "banned") {
                navController.navigate("login") { popUpTo(0) }
            }
            return@LaunchedEffect
        }

        val status = com.neveroff.data.remote.EnforcementRepository.fetchStatus()
        enforcement = status
        // Publish to the shared holder so every screen can grey out what it must.
        com.neveroff.data.remote.EnforcementState.update(status)

        val route = navController.currentDestination?.route
        if (status != null && status.blocksAccess) {
            if (route != "banned") {
                // popUpTo(0) clears the whole back stack: §4 requires that no
                // other surface remains reachable behind this screen.
                navController.navigate("banned") { popUpTo(0) }
            }
        } else if (route == "banned") {
            // Lifted, expired, or overturned — put them back in the app.
            navController.navigate("chat_list") { popUpTo(0) }
        }
    }

    // Rendered ahead of the NavHost, like the onboarding gates above, rather
    // than as a route. Restoring the key has to happen before any chat screen
    // reads a message, or the first sync caches "[Encrypted message]" against
    // rows the restore was about to make readable.
    val restoreUserId = pendingKeyRestoreUserId
    if (restoreUserId != null) {
        com.neveroff.ui.screens.RestoreFromBackupScreen(
            userId = restoreUserId,
            onRestored = {
                pendingKeyRestoreUserId = null
                // The local cache holds whatever failed to decrypt before the
                // real key arrived. Dropping it forces a clean resync, which is
                // the point of having restored the key at all.
                scope.launch {
                    runCatching { AppDatabase.resetInstance(context) }
                    enforcementCheckToken++
                }
            },
            onSkip = { pendingKeyRestoreUserId = null }
        )
        return
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
        // §4: the blocking screen. Reached only by a navigate("banned") that
        // clears the back stack, so there is nothing behind it to go back to,
        // and it registers no back handler of its own.
        composable("banned") {
            val current = enforcement
            if (current == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                com.neveroff.ui.screens.BanScreen(
                    status = current,
                    onRefresh = { enforcementCheckToken++ },
                    onOpenSupport = {
                        // Prefilled as an appeal when the reason allows one, so
                        // the person does not have to work out which category
                        // their suspension belongs in.
                        val appealable = current.reason?.appealable == true
                        navController.navigate(
                            if (appealable) "support/ban_appeal" else "support/other"
                        )
                    },
                    onSignOut = {
                        // Mirrors the Settings logout exactly. Clearing the
                        // cached profile matters here specifically: the offline
                        // fallback above routes to chat_list whenever a cached
                        // profile id exists, so leaving it behind would let a
                        // signed-out account back past this screen.
                        scope.launch {
                            runCatching { SupabaseClient.client.auth.signOut() }
                            runCatching {
                                context.getSharedPreferences(
                                    "neveroff_prefs", android.content.Context.MODE_PRIVATE
                                ).edit().clear().apply()
                            }
                            // Keeps the identity key: see signOutPreservingIdentity.
                            // Destroying it here made every past message in every
                            // chat unreadable on the next sign-in. The local
                            // database is left in place for the same reason —
                            // it is readable only with that key anyway, and
                            // dropping it forced a refetch that lost message
                            // ordering and timestamps.
                            runCatching { com.neveroff.crypto.CryptoManager.signOutPreservingIdentity() }
                            myProfile = null
                            enforcement = null
                            navController.navigate("login") { popUpTo(0) { inclusive = true } }
                        }
                    }
                )
            }
        }
        // §5 / §4: @support. Reachable from Settings normally, and from the ban
        // screen while suspended — the RPCs behind it ignore the enforcement
        // predicates, so this works when nothing else does.
        composable("support") {
            com.neveroff.ui.screens.SupportScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("support/{category}") { entry ->
            val category = entry.arguments?.getString("category")
            com.neveroff.ui.screens.SupportScreen(
                onBack = { navController.popBackStack() },
                initialCategoryKey = category,
                initialSubject = if (category == "ban_appeal") "Appeal against my suspension" else null
            )
        }
        composable("profile_setup") {
            ProfileSetupScreen(onComplete = {
                // The session is already Authenticated, so the status collector won't emit
                // again - resolve the freshly written profile here rather than relying on
                // it, or the rest of the app has no profile to show.
                loadCachedProfile(prefs)?.let { prof ->
                    myProfile = prof
                    com.neveroff.data.cache.ProfileCache.put(prof)
                }
                // Suggest the official channel once the account actually works.
                // Putting it earlier would mean asking someone to subscribe to
                // news about a product they have not finished signing up for.
                navController.navigate("official_channel") { popUpTo(0) }
            })
        }
        composable("backup_setup") {
            val backupUserId = com.neveroff.util.CurrentUser.id(context)
            com.neveroff.ui.screens.SetBackupPassphraseScreen(
                userId = backupUserId,
                hasExistingBackup = false,
                isOnboarding = true,
                onDone = { navController.navigate("chat_list") { popUpTo(0) } },
                // Skippable: a passphrase somebody was pressured into inventing
                // in ten seconds is one they will not remember, and an
                // unrememberable passphrase is the same outcome as no backup
                // with extra steps. The banner asks again later.
                onBack = { navController.navigate("chat_list") { popUpTo(0) } }
            )
        }
        composable("official_channel") {
            var channel by remember { mutableStateOf<com.neveroff.data.model.Channel?>(null) }
            var subscribing by remember { mutableStateOf(false) }
            var loadFailed by remember { mutableStateOf(false) }

            // Straight to the app: "loading" only resolves when the session
            // collector emits, and it does not emit again for an account that
            // was already authenticated when it arrived here.
            // Registration now ends on the backup step rather than the chat
            // list. Offering it here is the only moment it is free: there is no
            // history to lose yet, so setting a passphrase now protects
            // everything the account will ever send. Offered after the account
            // exists, because it seals a key that has to exist first.
            val finish: () -> Unit = {
                navController.navigate("backup_setup") { popUpTo(0) }
            }

            LaunchedEffect(Unit) {
                channel = runCatching {
                    channelRepository.channelByUsername(OFFICIAL_CHANNEL_USERNAME)
                }.getOrNull()
                // If the official channel cannot be resolved — no network, or it
                // does not exist in this environment — skip silently rather than
                // stranding a new user on a broken screen at the end of sign-up.
                if (channel == null) {
                    loadFailed = true
                    finish()
                }
            }

            val resolved = channel
            if (resolved != null && !loadFailed) {
                com.neveroff.ui.screens.OfficialChannelScreen(
                    channelName = resolved.name,
                    channelHandle = "n.me/${resolved.username}",
                    avatarUrl = resolved.avatar_url,
                    subscriberCount = resolved.subscriber_count,
                    isSubscribing = subscribing,
                    onSubscribe = {
                        subscribing = true
                        scope.launch {
                            val ok = runCatching {
                                channelRepository.subscribe(resolved.id)
                            }.getOrDefault(false)
                            subscribing = false
                            if (!ok) {
                                com.neveroff.ui.components.AppToast.error(
                                    "Couldn't subscribe right now — you can find it later in search"
                                )
                            }
                            finish()
                        }
                    },
                    onSkip = finish
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
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
                },
                onSetUpBackup = { navController.navigate("backup_setup_settings") }
            )
        }
        // Same screen as the registration step, reached from the reminder
        // banner. A separate route only so that "back" returns to the chat list
        // instead of replaying the end of sign-up.
        composable("backup_setup_settings") {
            com.neveroff.ui.screens.SetBackupPassphraseScreen(
                userId = com.neveroff.util.CurrentUser.id(context),
                hasExistingBackup = false,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
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
                            // Not resetKeys: that destroys the EC private key,
                            // and with it the only means of reading this
                            // account's history. Signing back in then showed
                            // every message as "[Encrypted message]".
                            com.neveroff.crypto.CryptoManager.signOutPreservingIdentity()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        try {
                            // Left in place deliberately. It is SQLCipher-encrypted
                            // with a key only this account can derive, and keeping
                            // it means the history does not have to be refetched —
                            // the refetch is what collapsed every timestamp to
                            // "now" and reordered chats by sender.
                            Unit
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
                onCreateChannel = {
                    // Blocked at the navigation edge rather than inside the
                    // create screen: letting someone fill in a name and an
                    // avatar before telling them they cannot publish is a
                    // worse experience than not opening the screen.
                    if (com.neveroff.data.remote.EnforcementState.isRestricted(
                            com.neveroff.data.remote.EnforcementState.Feature.CREATE_CHANNEL
                        )
                    ) {
                        com.neveroff.ui.components.AppToast.show(
                            context.getString(
                                com.neveroff.R.string.restriction_blocked_toast,
                                context.getString(com.neveroff.R.string.feature_create_channel)
                            )
                        )
                    } else navController.navigate("create_channel")
                },
                onChannelSelected = { channelId -> navController.navigate("channel/$channelId") },
                onOpenSupport = { navController.navigate("support") },
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

    // §4: a warning or a sending restriction. Drawn over the graph rather than
    // as a route inside it — it applies wherever the user happens to be, and a
    // route would have to fight the session collectors for the back stack the
    // way the onboarding screens above would have. MainActivity hosts this in a
    // Box, so it stacks on top of the NavHost and under the toast host.
    val notice = enforcement
    // Observed, not read off navController directly: currentDestination is not
    // snapshot state, so a plain read here would not recompose when the route
    // changes and the guard below would go stale.
    val currentEntry by navController.currentBackStackEntryAsState()
    if (
        notice != null &&
        notice.needsAcknowledgement &&
        notice.actionId != deferredNoticeActionId &&
        sessionStatus is SessionStatus.Authenticated &&
        // The ban screen states its own case; two enforcement screens at once
        // would just bury it.
        currentEntry?.destination?.route != "banned"
    ) {
        com.neveroff.ui.screens.WarningNoticeScreen(
            status = notice,
            onAcknowledged = {
                // Clear it locally so the overlay goes now, then re-check: the
                // server is the source of truth for whether it stays gone.
                enforcement = null
                enforcementCheckToken++
            },
            onDefer = { deferredNoticeActionId = notice.actionId },
            onOpenSupport = {
                deferredNoticeActionId = notice.actionId
                navController.navigate("support/other")
            }
        )
    }
}
