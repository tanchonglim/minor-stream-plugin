**⚠️ This is currently under development, dont use it yet if you're not comfortable with constantly merging new changes**

# `Cloudstream3 Plugin Repo Template`

Template for a [Cloudstream3](https://github.com/recloudstream) plugin repo

**⚠️ Make sure you check "Include all branches" when using this template**


## Getting started with writing your first plugin

This template includes 1 example plugin.

1. Open the root build.gradle.kts, read the comments and replace all the placeholders
2. Familiarize yourself with the project structure. Most files are commented
3. Build or deploy your first plugin using:
   - Windows: `.\gradlew.bat ExampleProvider:make` or `.\gradlew.bat ExampleProvider:deployWithAdb`
   - Linux & Mac: `./gradlew ExampleProvider:make` or `./gradlew ExampleProvider:deployWithAdb`


## Creating a New Provider in This Workspace

### Prerequisites

This workspace requires **Java 17** to build. The default Java version (25) is incompatible. Always prefix Gradle commands with:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew ...
```

### Step 1: Scaffold the provider

Create the directory structure:

```
MyProvider/
  build.gradle.kts
  src/main/kotlin/com/example/
    MyPlugin.kt
    MyProvider.kt
```

**`build.gradle.kts`** — minimal template:
```kotlin
version = 1

cloudstream {
    description = "My site description"
    authors = listOf("yourname")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "zh"
    iconUrl = "https://www.google.com/s2/favicons?domain=yoursite.com&sz=64"
}
```

**`MyPlugin.kt`** — boilerplate (copy from any existing provider):
```kotlin
package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyProvider())
    }
}
```

**`MyProvider.kt`** — extend `MainAPI()` and override:
- `getMainPage(page, request)` — category listing, paged
- `search(query)` — keyword search
- `load(url)` — detail page, returns episodes + metadata
- `loadLinks(data, ...)` — extract playable m3u8/video URLs

See any existing provider (e.g. [CupfoxProvider](CupfoxProvider/src/main/kotlin/com/example/CupfoxProvider.kt)) for a full working example.

### Step 2: Research the target site

Before writing the provider, understand how the site works:

1. **Fetch the homepage** with `curl -s -A "Mozilla/5.0" https://example.com` to find category URL patterns.
2. **Find the movie listing and detail URL patterns** (e.g. `/detail/{id}`, `/vod/show/id/{type}`).
3. **Find the video API**:
   - Check for a `__NEXT_DATA__` script (Next.js sites).
   - Grep the JS bundles for `m3u8`, `player_aaaa`, `encrypt`, `playUrl`, `/api/`.
   - For Next.js apps, fetch `/_next/static/chunks/*.js` and search for API paths and signing logic.
4. **Test the API** with Python `urllib` before writing Kotlin.

### Step 3: Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :MyProvider:build
```

On success the `.cs3` plugin file is output under `MyProvider/build/`.

### Step 4: Deploy for testing

**Via ADB (Android device/emulator):**
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :MyProvider:deployWithAdb
```

**Manual sideload:** Copy the `.cs3` file to the device, open it in the Cloudstream3 file manager, or place it in a local repository.

**Local repo:** Start a simple HTTP server in `build/` and point Cloudstream3 at `http://<your-ip>:8080/repo.json`.

### Existing providers for reference

| Provider | Site | Key technique |
|---|---|---|
| [CupfoxProvider](CupfoxProvider/) | cupfox.in | JSON API + tea endpoint |
| [OlevodProvider](OlevodProvider/) | olevod.com | `player_aaaa` JS config + Base64/URLdecode |
| [DubokuProvider](DubokuProvider/) | duboku.ru | HTML scraping + player config |
| [IyftvProvider](IyftvProvider/) | iyf.tv | HTML scraping |
| [Kuhh4joProvider](Kuhh4joProvider/) | kuhh4jo.com | Next.js + signed REST API (HMAC MD5+SHA1) |


## Granting All Files Access on Newer Android Devices

For local plugin testing, you need to grant the app "All Files Access" on newer Android devices (Android 11 and above). Here’s how to do it:

### Using ADB

* `adb shell appops set --uid PACKAGE_NAME MANAGE_EXTERNAL_STORAGE allow`
* Replace `PACKAGE_NAME` with the name of the package for the Cloudstream3 version you are using:
   - debug: `com.lagradost.cloudstream3.prerelease.debug`
   - prerelease: `com.lagradost.cloudstream3.prerelease`
   - stable: `com.lagradost.cloudstream3`

### Manually

1. **Open Settings**: Go to your device’s Settings menu.

2. **Navigate to Special Access**:
   - Tap on "Apps & notifications" or "Apps".
   - Select "Special app access" or "Special access".

3. **Select All Files Access**:
   - Tap on "All files access".
   - It may be under the three vertical dots menu towards the top of the screen.

4. **Grant Access to the App**: Find the app in the list and tap on it to toggle it, if it is not already enabled.

6. **Restart the App**: Close and reopen the app to apply the changes.


## License

Everything in this repo is released into the public domain. You may use it however you want with no conditions whatsoever


## Attribution

This template as well as the gradle plugin and the whole plugin system is **heavily** based on [Aliucord](https://github.com/Aliucord).
*Go use it, it's a great mobile discord client mod!*
