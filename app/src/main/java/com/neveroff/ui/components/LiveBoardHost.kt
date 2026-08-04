package com.neveroff.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neveroff.R
import com.neveroff.data.remote.LiveBoardSession
import kotlinx.coroutines.launch

/**
 * Owns a board for one chat: the connection, the strokes, and who is present.
 *
 * Renders nothing at all until somebody opens or is invited to a board, so it
 * can sit unconditionally in the chat screen's overlay.
 */
@Composable
fun LiveBoardHost(
    chatId: String,
    myId: String,
    authKey: ByteArray?,
    partnerName: String,
    /** Set true by the chat screen when the user picks "Live board". */
    startRequested: Boolean,
    onStartHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (authKey == null) return

    val scope = rememberCoroutineScope()
    val session = remember(chatId, authKey) { LiveBoardSession(chatId, myId, authKey) }

    // Resolved here, in composable scope: the flow collector below runs outside
    // one and cannot call stringResource.
    val closedMessage = stringResource(R.string.board_ended)

    var open by remember { mutableStateOf(false) }
    var invitedBy by remember { mutableStateOf<String?>(null) }
    var partnerJoined by remember { mutableStateOf(false) }
    val strokes = remember { mutableStateListOf<BoardStroke>() }

    DisposableEffect(session) {
        scope.launch { runCatching { session.connect() } }
        onDispose { session.disconnect() }
    }

    LaunchedEffect(session) {
        session.events.collect { event ->
            when (event) {
                is LiveBoardSession.Event.Invited -> {
                    // Ignore an invitation while already drawing: both sides
                    // opening at once would otherwise show each other a prompt
                    // for a board they are already in.
                    if (!open) invitedBy = event.fromUserId
                }
                is LiveBoardSession.Event.Joined -> {
                    partnerJoined = true
                    // Replay what is already on the board. Strokes are
                    // broadcast, not stored, so somebody who joins a minute
                    // later has missed every message sent before they
                    // subscribed and would see an empty canvas next to a
                    // drawing. Re-sending our own gives them the picture.
                    val existing = strokes.filter { it.mine }
                    existing.forEach { s ->
                        runCatching {
                            session.draw(
                                LiveBoardSession.Stroke(
                                    s.id, myId, s.color, s.width, s.points, done = true
                                )
                            )
                        }
                    }
                }
                is LiveBoardSession.Event.Drew -> {
                    val s = event.stroke
                    // Replace by id rather than append: a stroke arrives in
                    // pieces while the finger is still down, and appending each
                    // batch would stack every prefix of the line on top of
                    // itself, thickening it as it grows.
                    val idx = strokes.indexOfFirst { it.id == s.id }
                    val mapped = BoardStroke(s.id, s.color, s.width, s.points, mine = false)
                    if (idx >= 0) strokes[idx] = mapped else strokes.add(mapped)
                }
                LiveBoardSession.Event.Cleared -> strokes.clear()
                LiveBoardSession.Event.Closed -> {
                    // Closing is mutual by design: a shared surface one person
                    // can leave open on the other's screen is a way to keep
                    // drawing at somebody who has stopped looking.
                    open = false
                    invitedBy = null
                    partnerJoined = false
                    strokes.clear()
                    AppToast.show(closedMessage)
                }
            }
        }
    }

    LaunchedEffect(startRequested) {
        if (startRequested) {
            open = true
            partnerJoined = false
            strokes.clear()
            runCatching { session.invite() }
            onStartHandled()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        invitedBy?.let {
            BoardInvitation(
                partnerName = partnerName,
                onJoin = {
                    invitedBy = null
                    open = true
                    partnerJoined = true
                    scope.launch { runCatching { session.join() } }
                },
                onDecline = { invitedBy = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
            )
        }

        if (open) {
            LiveBoardWindow(
                strokes = strokes,
                partnerJoined = partnerJoined,
                onStroke = { id, points, color, width, done ->
                    val idx = strokes.indexOfFirst { it.id == id }
                    val mine = BoardStroke(id, color, width, points, mine = true)
                    if (idx >= 0) strokes[idx] = mine else strokes.add(mine)
                    scope.launch {
                        runCatching {
                            session.draw(
                                LiveBoardSession.Stroke(id, myId, color, width, points, done)
                            )
                        }
                    }
                },
                onClear = {
                    strokes.clear()
                    scope.launch { runCatching { session.clear() } }
                },
                onClose = {
                    open = false
                    partnerJoined = false
                    strokes.clear()
                    scope.launch { runCatching { session.close() } }
                },
                // Top of the screen, clear of the chat header, rather than
                // centred: a board in the middle covers the messages the
                // drawing is usually about.
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
            )
        }
    }
}

/**
 * The invitation the other person sees: a bubble, not a dialog.
 *
 * A dialog would seize the screen for something entirely optional, in the
 * middle of a conversation somebody may be busy reading.
 */
@Composable
private fun BoardInvitation(
    partnerName: String,
    onJoin: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.board_invite_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                stringResource(R.string.board_invite_body, partnerName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDecline) {
                    Text(stringResource(R.string.board_decline))
                }
                Button(onClick = onJoin) {
                    Text(stringResource(R.string.board_join))
                }
            }
        }
    }
}
