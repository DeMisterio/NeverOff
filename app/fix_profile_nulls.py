import os
import re

def fix_file(path, replacements):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    for old, new in replacements:
        content = content.replace(old, new)
        
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

base_dir = r"c:\Users\USER A\Documents\NeverOff\app\src\main\java\com\neveroff"

# MainActivity.kt
fix_file(os.path.join(base_dir, "MainActivity.kt"), [
    ('putString("profile_username", prof.username)', 'putString("profile_username", prof.username ?: "")'),
    ('putString("profile_display_name", prof.display_name)', 'putString("profile_display_name", prof.display_name ?: "Me")'),
    ('putString("profile_public_key", prof.public_key)', 'putString("profile_public_key", prof.public_key ?: "")'),
    ('putString("profile_display_name", newName)', 'putString("profile_display_name", newName ?: "Me")'),
    ('putString("profile_display_name", updated.display_name)', 'putString("profile_display_name", updated.display_name ?: "Me")'),
    ('android.net.Uri.encode(profile.display_name)', 'android.net.Uri.encode(profile.display_name ?: "Me")')
])

# ChatListScreen.kt
fix_file(os.path.join(base_dir, "ui", "screens", "ChatListScreen.kt"), [
    ('currentProfile.display_name.take(1).uppercase()', '(currentProfile.display_name ?: "Me").take(1).uppercase()')
])

# EditProfileBottomSheet.kt
fix_file(os.path.join(base_dir, "ui", "screens", "EditProfileBottomSheet.kt"), [
    ('var displayName by remember(profile) { mutableStateOf(profile.display_name) }', 'var displayName by remember(profile) { mutableStateOf(profile.display_name ?: "Me") }'),
    ('profile.display_name.take(1).uppercase()', '(profile.display_name ?: "Me").take(1).uppercase()')
])

# ProfileDialog.kt
fix_file(os.path.join(base_dir, "ui", "screens", "ProfileDialog.kt"), [
    ('profile!!.display_name.take(1).uppercase()', '(profile!!.display_name ?: "Me").take(1).uppercase()'),
    ('text = profile!!.display_name,', 'text = profile!!.display_name ?: "Me",')
])

# ProfileSetupScreen.kt
fix_file(os.path.join(base_dir, "ui", "screens", "ProfileSetupScreen.kt"), [
    ('putString("profile_username", profile.username)', 'putString("profile_username", profile.username ?: "")'),
    ('putString("profile_display_name", profile.display_name)', 'putString("profile_display_name", profile.display_name ?: "Me")')
])

# SearchUserScreen.kt
fix_file(os.path.join(base_dir, "ui", "screens", "SearchUserScreen.kt"), [
    ('displayName = profile.display_name,', 'displayName = profile.display_name ?: "Me",'),
    ('username = profile.username,', 'username = profile.username ?: "",')
])

# UserProfileScreen.kt
fix_file(os.path.join(base_dir, "ui", "screens", "UserProfileScreen.kt"), [
    ('profile.display_name.take(1).uppercase()', '(profile.display_name ?: "Me").take(1).uppercase()'),
    ('text = profile.display_name,', 'text = profile.display_name ?: "Me",')
])

# ChatListViewModel.kt
fix_file(os.path.join(base_dir, "ui", "viewmodels", "ChatListViewModel.kt"), [
    ('otherUserName = profile.display_name,', 'otherUserName = profile.display_name ?: "Me",')
])
