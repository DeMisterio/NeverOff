import re

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# 1. Add OptIn
optin_search = """@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen("""
optin_replace = """@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen("""
content = content.replace(optin_search, optin_replace)

# 2. Add animateItemPlacement
items_search = """            items(uiMessages.reversed(), key = { it.id }) { msg ->
                MessageBubble(
                    msg = msg, 
                    allMessages = uiMessages,"""
items_replace = """            items(uiMessages.reversed(), key = { it.id }) { msg ->
                Box(modifier = Modifier.animateItemPlacement()) {
                    MessageBubble(
                        msg = msg, 
                        allMessages = uiMessages,"""
content = content.replace(items_search, items_replace)

close_search = """                    onSelect = { 
                        if (msg.id != null) {
                            if (selectedMessages.contains(msg.id)) selectedMessages.remove(msg.id) 
                            else selectedMessages.add(msg.id) 
                        }
                    }
                )
            }
        }
        }"""
close_replace = """                    onSelect = { 
                        if (msg.id != null) {
                            if (selectedMessages.contains(msg.id)) selectedMessages.remove(msg.id) 
                            else selectedMessages.add(msg.id) 
                        }
                    }
                )
                }
            }
        }
        }"""
content = content.replace(close_search, close_replace)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.write(content)
