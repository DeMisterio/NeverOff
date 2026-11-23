# NeverOff

NeverOff is an Android messenger for private chats, groups, and broadcast channels. Private chats use end-to-end encryption. Channel posts are readable by people with access to the channel.

The latest distributed Android build is **3.4**. The `main` branch currently declares **2.12** in Gradle. The source for 3.4 is at [the 3.4 release commit](https://github.com/DeMisterio/NeverOff/commit/068bd54c10db5feb6a96981d4c22c7bdbed4355e).

## Build locally

You need Android Studio, JDK 17, and Android SDK 34. The app supports Android 8.0 (API 26) and later.

1. Clone this repository and open it in Android Studio.
2. Set up a Supabase project and apply the migrations in [`supabase/migrations`](supabase/migrations). Edge Functions are in [`supabase/functions`](supabase/functions).
3. Create a `.env` file in the repository root with your Supabase project URL and publishable key.

   ```dotenv
   SUPABASE_URL=https://your-project.supabase.co
   SUPABASE_PUBLISHABLE_KEY=your-publishable-key
   ```

4. For Firebase Cloud Messaging, use a Firebase Android app registered as `com.neveroff` and place its `google-services.json` in `app/`.
5. Run the app from Android Studio or build a debug APK with `./gradlew :app:assembleDebug` (`gradlew.bat :app:assembleDebug` on Windows).

The Android client lives in [`app/`](app/). It uses Kotlin and Jetpack Compose, Room with SQLCipher for local storage, and Supabase for authentication, storage, and realtime updates. The native cryptography source is in [`rust/`](rust/).

## Release history

These notes come from NeverOff’s in-app update history. Versions use the names shown in the app.

### 3.4

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-3.4.apk) · [Source commit](https://github.com/DeMisterio/NeverOff/commit/068bd54c10db5feb6a96981d4c22c7bdbed4355e)

Reactions save again, and a channel can now allow up to ten of them.

Media: messages that were delivered no longer show as failed, your photo or video appears straight away while it uploads with a progress ring and a cancel button, and a failed send can be retried without sending twice. Video sent through Files arrives as video you can play and save under its original name. Round video notes now come only from the record button. Background music pauses while something with sound is playing.

You can change your username, and keep more than one pointing at your account.

New appearance settings: pick an accent colour, or set your own bubble colours, plus a chat wallpaper from a picture of your own with dim and blur. Text contrast adjusts itself so it always stays readable.

Fixes: the reaction picker scrolls to the end, message fields keep their shape as they grow, the recording circle sits above the conversation, and the camera badge, back button and dialogs are no longer clipped or see-through.

### 3.2

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-3.2.apk) · [Source commit](https://github.com/DeMisterio/NeverOff/commit/e5dd73ef1c07efd3006555668979317aa902499c)

Live board: draw together in real time, straight from the attachment menu. Attachments now open below the composer and files show what type they are. Russian throughout. True black theme, auto-delete down to an hour, more channel reactions. Profiles no longer vanish offline.

### 3.06

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-3.06.apk) · [Source commit](https://github.com/DeMisterio/NeverOff/commit/e38e20200d5d0bdb32582ff8dd290ad923a20ad0)

Fixes the app closing itself right after registering a new account.

### 3.05

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-3.05.apk) · [Source commit](https://github.com/DeMisterio/NeverOff/commit/2a4e18149ca40120939f81e6da83bc69afb6d79f)

Message and channel post times are correct again instead of all showing the moment they were downloaded. Channel posts read like received messages, newest at the bottom. You can now forward a channel post into a chat, with the channel attached.

### 3.04

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-3.04.apk) · [Source commit](https://github.com/DeMisterio/NeverOff/commit/19e99c2f5ce2e231cf9dbd4ce7bc62d22fe67975)

Fixes a crash when setting up chat backup. Adds date separators between days in chats and channels. Channel posts now read top to bottom with the newest at the end. New accounts are offered a recovery passphrase during sign-up.

### 3.03

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-3.03.apk) · [Source commit](https://github.com/DeMisterio/NeverOff/commit/02a7c54568c7b0bb2f9f6688cca73f512a2770da)

Signing out no longer makes your messages unreadable. Adds an optional recovery passphrase so your history can be restored on a new device. Account restrictions now show up properly in the app. Fixes the first-launch language choice not applying.

### 3.0

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-3.0.apk) · [Source commit](https://github.com/DeMisterio/NeverOff/commit/904e0b46e406ebde2157c9a236819acace7332fe)

Choose your language and review the Terms and Privacy Policy when you sign up. Read receipts now only show when the message was actually opened. Screenshots are blocked only while a temporary message is on screen. Check for updates manually from Settings. Messages are now deleted for everyone.

### 2.12

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-2.12.apk)

Fixed screenshot restrictions, temporary message UI, navigation crashes, read status sync, and background service initialization.

### 2.11

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-2.11.apk)

Fixed: the in-app updater compared version numbers wrongly and ranked 2.02 above 2.1. Some phones were offered an older build as an "update" (which then failed to install), and phones on 2.01 or 2.02 were never offered 2.1 at all.

If you were stuck on an older build, this brings you up to date - including Channels, which shipped in 2.1.

### 2.1

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-2.1.apk)

Channels are here.

What you can do:
- Tap + to create a channel and broadcast posts to everyone who subscribes
- Public channels show up in search; private ones are reachable only by link
- Share a channel by pasting its n.me link into any chat
- React to posts, and see how many people have read each one
- Link your Instagram, Snapchat, TikTok or Telegram on the channel page

Note: channel posts are readable by anyone who opens the channel, so unlike your chats they are not end-to-end encrypted.

### 2.0.2

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-2.02.apk)

Fixes:
- Voice and circle recording no longer stops working after a failed attempt - the microphone was being held open.
- Starting a recording while a message was sending could silently do nothing.
- Recordings that are too short are now discarded instead of sending an empty file.

Round video notes:
- Recording view is now centred and larger.
- Sent notes lose the square bubble - a thin ring with the time in a pill underneath.
- The timeline now shows a marker so you can see exactly where you are seeking.

Updates:
- New "Autodownload updates" setting.
- Download progress now shows inside chats and can be stopped at any time.
- Updates are always reachable from Settings, even if you hide the banner.
- Redesigned "What's new" screen.

Other:
- Voice bubbles grow less when scrubbing, so they no longer overlap messages above.
- Clear "Can't connect" warning with troubleshooting help after repeated failures.

### 2.0.1

[Android APK](https://exnhmyaolukfaupphhxq.supabase.co/storage/v1/object/public/app-releases/app-2.01.apk)

Voice messages now show a real waveform you can scrub. Drag slowly and the bubble grows for precise seeking; drag past either end and it stretches.

Round video messages have a perimeter timeline - drag clockwise from the top to seek. Keep winding past a full turn and the circle spins with your finger, then springs back.

Starting a circle message is smooth now: the record button grows into the viewfinder instead of showing a black gap while the camera opens.

Swipe left while recording to cancel - this previously did nothing.

New "Voice & circle" animation preview in Appearance, and the full-experience preview now includes the voice timeline.

"Add user" is now a labelled button instead of a bare +.

Fixed: with no connection, every message in a chat appeared as though it came from the other person.

Fixed: update checks now pick the highest version rather than whichever row came back last.

## License

[MIT](LICENSE)
