package com.neveroff.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neveroff.legal.LegalConsent
import com.neveroff.legal.LegalTexts
import com.neveroff.util.AppLanguage

/**
 * First-run onboarding: pick a language, then read and accept the legal terms.
 *
 * The order is deliberate. Consent to a document somebody could not read is not
 * consent, so the language question comes before the agreement rather than
 * sitting in Settings where it would arrive too late to matter.
 */

@Composable
fun LanguagePickerScreen(onChosen: (AppLanguage.Language) -> Unit) {
    // Preselect the device language as a guess, but require an actual tap: a
    // preselected radio button that the user never touched is not a choice.
    var selected by remember { mutableStateOf(AppLanguage.Language.deviceDefault()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            Text(
                text = "NeverOff",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(8.dp))

            // Shown in both languages, because at this point we do not yet know
            // which one they read.
            Text(
                text = "Choose your language\nВыберите язык",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(36.dp))

            AppLanguage.Language.entries.forEach { language ->
                val isSelected = selected == language
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { selected = language }
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = language.nativeName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (language.nativeName != language.englishName) {
                                Text(
                                    text = language.englishName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { onChosen(selected) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (selected == AppLanguage.Language.RUSSIAN) "Продолжить" else "Continue",
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (selected == AppLanguage.Language.RUSSIAN)
                    "Это язык, на котором будут показаны условия использования."
                else
                    "This is the language the terms will be shown in.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Terms of Use and Privacy Policy, with agreement gated on actually reaching the
 * end of the text.
 *
 * The scroll gate is not friction for its own sake — it is the difference
 * between a record that someone agreed and a record that someone was shown a
 * button. It costs a determined skimmer a flick; it stops the case where the
 * agree button was the only thing on screen.
 */
@Composable
fun LegalConsentScreen(
    language: AppLanguage.Language,
    onAccepted: () -> Unit,
    onChangeLanguage: () -> Unit
) {
    val ru = language == AppLanguage.Language.RUSSIAN
    val scrollState = rememberScrollState()
    var showRejectWarning by remember { mutableStateOf(false) }

    val terms = remember(language) { LegalTexts.terms(language) }
    val privacy = remember(language) { LegalTexts.privacy(language) }

    // maxValue is 0 until the content has been measured; treating that as
    // "already at the bottom" would defeat the gate on first composition.
    val reachedBottom by remember {
        derivedStateOf {
            scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 24
        }
    }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(20.dp)) {
                    if (!reachedBottom) {
                        Text(
                            text = if (ru)
                                "Пролистайте до конца, чтобы продолжить."
                            else
                                "Scroll to the end to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    Button(
                        onClick = onAccepted,
                        enabled = reachedBottom,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = if (ru) "Принимаю" else "I agree",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    TextButton(
                        onClick = { showRejectWarning = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (ru) "Отклонить" else "Decline",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (ru) "Прежде чем начать" else "Before you start",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onChangeLanguage) {
                    Text(language.nativeName)
                }
            }

            Spacer(Modifier.height(20.dp))

            LegalDocument(terms, ru)
            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(32.dp))
            LegalDocument(privacy, ru)

            Spacer(Modifier.height(28.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (ru)
                        "Нажимая «Принимаю», вы подтверждаете, что прочитали оба документа и " +
                            "согласны с ними. Мы сохраним дату согласия и версию текста, который " +
                            "вам показали."
                    else
                        "By tapping \"I agree\" you confirm you have read both documents and " +
                            "accept them. We record when you agreed and which version of the " +
                            "text you were shown.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showRejectWarning) {
        AlertDialog(
            onDismissRequest = { showRejectWarning = false },
            title = {
                Text(if (ru) "Без согласия пользоваться нельзя" else "You cannot use NeverOff without agreeing")
            },
            text = {
                Text(
                    if (ru)
                        "Эти документы описывают, как работает шифрование, что мы храним и что " +
                            "происходит при нарушении правил. Без вашего согласия мы не можем " +
                            "создать аккаунт и обрабатывать ваши данные.\n\n" +
                            "Вы можете вернуться и прочитать их ещё раз, или закрыть приложение."
                    else
                        "These documents describe how the encryption works, what we store, and " +
                            "what happens if the rules are broken. Without your agreement we " +
                            "cannot create an account or process your data.\n\n" +
                            "You can go back and read them again, or close the app."
                )
            },
            confirmButton = {
                TextButton(onClick = { showRejectWarning = false }) {
                    Text(if (ru) "Вернуться" else "Go back")
                }
            }
        )
    }
}

@Composable
private fun LegalDocument(document: LegalTexts.Document, ru: Boolean) {
    Text(
        text = document.title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = if (ru) "Обновлено ${document.lastUpdated}" else "Updated ${document.lastUpdated}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = document.intro,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    )

    document.sections.forEach { section ->
        Spacer(Modifier.height(20.dp))
        Text(
            text = section.heading,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = section.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
    }
}

/**
 * Suggests the official news channel once the account exists.
 *
 * Deliberately skippable and deliberately last: a subscription prompt during
 * sign-up that blocks progress is a dark pattern, and this one is only useful
 * to someone who has already got an account working.
 */
@Composable
fun OfficialChannelScreen(
    channelName: String,
    channelHandle: String,
    avatarUrl: String?,
    subscriberCount: Int?,
    isSubscribing: Boolean,
    onSubscribe: () -> Unit,
    onSkip: () -> Unit
) {
    val ru = AppLanguage.current.collectAsState().value == AppLanguage.Language.RUSSIAN

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            Text(
                text = if (ru) "Официальный канал" else "Official news channel",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = if (ru)
                    "Здесь мы публикуем обновления, изменения в правилах и предупреждения о " +
                        "безопасности. Это единственный канал, которому мы пишем от лица NeverOff."
                else
                    "Where we post updates, rule changes and security notices. It is the only " +
                        "channel we post to as NeverOff.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
            )

            Spacer(Modifier.height(32.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        // The initial sits underneath and the avatar loads on
                        // top. AsyncImage draws nothing when a load fails, so
                        // the letter simply stays visible — no error slot and
                        // no empty circle if the network is down mid-signup.
                        Text(
                            channelName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (!avatarUrl.isNullOrBlank()) {
                            coil.compose.AsyncImage(
                                model = avatarUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = channelName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            // Verified marker carries a text label in the
                            // content description, not colour alone.
                            Icon(
                                Icons.Default.Check,
                                contentDescription = if (ru) "Официальный" else "Official",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = channelHandle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (subscriberCount != null && subscriberCount > 0) {
                            Text(
                                text = if (ru) "$subscriberCount подписчиков"
                                       else "$subscriberCount subscribers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onSubscribe,
                enabled = !isSubscribing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isSubscribing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = if (ru) "Подписаться" else "Subscribe",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (ru) "Не сейчас" else "Not now",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (ru)
                    "Отписаться можно в любой момент."
                else
                    "You can unsubscribe at any time.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )
        }
    }
}
