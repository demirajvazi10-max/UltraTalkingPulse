# Ultra Talking Pulse

**Pulse and breathing from your phone's camera, with spoken guidance that gets you into position.**

Ultra Talking Pulse is an Android app built on the [Presage SmartSpectra SDK](https://github.com/Presage-Security/SmartSpectra). It estimates your pulse rate and breathing rate from the front camera. Camera vitals only work when your face is centred, well lit and still. Most apps expect you to check that on the screen. Ultra Talking Pulse tells you instead: "too far, move closer", "too dark, turn on a light", "good, hold still". Anyone can set it up without looking at the screen, whether they're blind, have low vision, have the phone propped across the room, or simply aren't holding it.

Part of the Ultra family of accessible software. *Creative tools that start with the keyboard, not the mouse.*

> **Not a medical device.** Ultra Talking Pulse is for general wellness and curiosity only. It must not be used for diagnosis, treatment or any medical decision.

## How it works

1. Prop the phone upright on a table, screen facing you, about an arm's length away.
2. Press **Start measurement**.
3. The app listens to the SDK's live validation status (face found, centred, distance, lighting, motion and so on) and speaks one short instruction at a time until your position is good. A tone and a vibration confirm when you get there.
4. It measures for about 40 seconds, and the screen stays on for the whole measurement so the phone never locks mid-way. Only time spent in a good position counts, so if you move, the measurement pauses and you're told how to fix it.
5. You hear the result, for example "Pulse: about 72 beats per minute, high confidence. Breathing: about 14 breaths per minute, medium confidence."

### Live signals used

| Signal | How Ultra Talking Pulse uses it |
| --- | --- |
| Validation status (16 codes) | Turned into short spoken instructions, spoken only when the status changes and has settled |
| Pulse rate + confidence | Final result, with a plain-language confidence word |
| Breathing rate + confidence | Final result, with a plain-language confidence word |
| Talking detection | A gentle "please don't talk while measuring" (at most every 12 seconds) |

### Accessibility design

- **Works with TalkBack.** When a screen reader is running, messages are sent through it, so they use your own voice and speed and never talk over TalkBack. Without a screen reader, the app speaks with Android's text-to-speech, so sighted users get the same guidance.
- **Quiet by design.** A message is spoken only when the situation changes and has stayed that way for almost a second. No running countdown chatter. The progress line is on screen, and **Repeat last message** reads it on request.
- **Not just for blind users.** The camera preview is visible for sighted users, but the app never relies on it.
- Large buttons (at least 56 dp), real headings for quick navigation, high-contrast dark theme, portrait layout with no hidden gestures.
- Speech and sounds/vibration can each be switched off.
- **About** and **How to support this project** are available from the main screen, in English and Serbian.
- **Bilingual:** English and Serbian (Latin script). The language follows the phone, or can be set per app in Android 13+. Serbian speech falls back to a Croatian or Bosnian voice if no Serbian voice is installed.

## Consent and data handling

- **Camera consent.** On first launch, a privacy notice explains everything below, and the app can't be used until you accept it. After that, Android's own camera permission is requested the first time you press Start. The camera is on only while a measurement is running.
- **No storage.** Ultra Talking Pulse never records, saves or uploads video or images. Results are only shown on screen and are lost when the app closes. There is no history, no account and no analytics. Android backup is disabled for the app.
- **Processing.** Frames are processed on the device by the Presage SmartSpectra SDK. The SDK connects to Presage's servers to authenticate the API key and track usage.
- **SDK telemetry.** The SmartSpectra SDK can send an anonymous, aggregate per-session summary (no frames, no vitals values, no identifiers; see Presage's [Telemetry & Privacy](https://github.com/Presage-Security/SmartSpectra/blob/main/docs/telemetry-and-privacy.md)). **Ultra Talking Pulse turns it off** (`enableTelemetry = false`).
- **No AI services.** The SDK's optional LLM insight feature is not used.
- **Measuring other people.** The notice asks you to measure only yourself or someone who has agreed to it. The front camera is used, so the person being measured is the one facing the screen.
- **Withdrawing consent.** The **Privacy and consent** button shows the notice again and lets you withdraw consent, which closes the app until you accept again.

## Build it yourself

Requirements: JDK 17 or newer, the Android SDK (platform 36.1), and a physical Android phone with Android 9 (API 28) or newer. The emulator can't be used because a real camera is needed.

1. Create a free account on the [Presage developer portal](https://physiology.presagetech.com/auth/register).
2. Give the app Presage credentials in **one** of two ways:
   - **OAuth (recommended, no key inside the app).** Run `gradlew signingReport` and copy the SHA-256 fingerprint of the `debug` variant. In the portal, open **OAuth Registration**, choose Android, enter the package name `com.demirajvazi.ultratalkingpulse` and the fingerprint, and register. Download `presage_services.xml` and put it in `app/src/main/res/xml/`. The file is git-ignored. You can also do the registration with an AI assistant connected to the [SmartSpectra MCP server](https://github.com/Presage-Security/SmartSpectra/blob/main/docs/mcp-server.md).
   - **API key.** Create or open `local.properties` in the project root (it is git-ignored) and add:
     ```properties
     sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
     presage.apiKey=YOUR_API_KEY
     ```
     You can also set the `SMARTSPECTRA_API_KEY` environment variable instead. When a key is set, it is used; otherwise the app uses OAuth.
3. Build and install with the phone connected over USB:
   ```bat
   gradlew installDebug
   ```
   Or just build the APK with `gradlew assembleDebug`. You'll find it in `app\build\outputs\apk\debug\`.

### Automatic builds on GitHub

`.github/workflows/build.yml` builds the APK on every push to `main` (download it from the run's **Artifacts**). A tag that starts with `v` (for example `v1.0.0`) also publishes a GitHub release named after the tag, with `UltraTalkingPulse-v1.0.0-android.apk` attached.

For the published APK to sign in to Presage, the workflow needs two repository secrets (**Settings > Secrets and variables > Actions**):

| Secret | What it holds |
| --- | --- |
| `PRESAGE_SERVICES_XML` | The text of `app/src/main/res/xml/presage_services.xml` |
| `DEBUG_KEYSTORE_BASE64` | The signing keystore whose SHA-256 is registered with Presage, base64-encoded |

On Windows, `pripremi-github-tajne.cmd` puts each value on the clipboard in turn and opens the page where it is added. Without the secrets the APK still builds, but it cannot sign in to Presage.

Every installed copy uses the Presage credits of the account the app is registered to, so keep an eye on usage in the Presage portal.

## Tech

- Kotlin, a single activity, no XML layouts, no third-party UI libraries
- `com.presagetech:smartspectra:3.4.0`, AndroidX CameraX `PreviewView`
- Android Gradle Plugin 9.2.1 (built-in Kotlin), Gradle 9.4.1, minSdk 28, targetSdk 36

## Supporting the project

Ultra Talking Pulse is free and will stay free. If you'd like to support its development:

- **By card on [Ko-fi](https://ko-fi.com/ultracreativesuite)**: no account needed. Type an amount, press *Donate* and pay with a Visa or Mastercard (credit or debit) or PayPal. The easiest way from abroad.
- **[GitHub Sponsors](https://github.com/sponsors/demirajvazi10-max)**: monthly or one-time, paid with an ordinary payment card (no PayPal needed); needs a free GitHub account.
- **Bank transfer from Serbia**: recipient Demir Ajvazi, account `265-0000006185877-28`, Raiffeisen banka, purpose *Donacija za Ultra programe*. Meant for domestic transfers; from abroad, Ko-fi costs far less in fees.

The same guide is inside the app: **How to support this project** on the main screen (also reachable from **About**), in English and Serbian, with buttons to open Ko-fi or GitHub Sponsors and to copy the account number.

## Author

Created by **Demir Ajvazi**. Support development on [GitHub Sponsors](https://github.com/sponsors/demirajvazi10-max).

[GitHub](https://github.com/demirajvazi10-max) · [LinkedIn](https://linkedin.com/in/demir-ajvazi) · [X](https://x.com/demirprogramer)

## License

GPL-3.0. See [LICENSE](LICENSE).

The Presage SmartSpectra SDK is proprietary software under [Presage's Terms of Service](https://physiology.presagetech.com/tos) and is not covered by this license. To make that combination clear, the author grants this additional permission under section 7 of the GPL-3.0:

> If you modify this Program, or any covered work, by linking or combining it with the Presage SmartSpectra SDK (or a modified version of that library), containing parts covered by the terms of the Presage Terms of Service, the licensors of this Program grant you additional permission to convey the resulting work.
