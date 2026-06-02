# CloudStream3 Chinese Plugin Repo — Developer Notes

## Build Requirements

**Java 17 is required.** The default environment JVM (25) is incompatible with the Android Gradle plugin.
Always prefix Gradle commands with:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :ProviderName:build
```

To deploy via ADB:
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :ProviderName:deployWithAdb
```

## Versioning Rule

**Always increment `version` in `build.gradle.kts` whenever you make any change to a provider.**
Cloudstream3 uses this integer to detect and apply updates on connected devices.

```kotlin
// bump this whenever the provider changes
version = 5  // was 4
```

## Provider Overview

| Provider | Site | Version | Technique |
|---|---|---|---|
| [DnvodProvider](DnvodProvider/) | dnvod.org | 8 | HTML scrape + player config |
| [DubokuProvider](DubokuProvider/) | duboku.ru | 8 | HTML scrape + `player_aaaa` JS config |
| [CupfoxProvider](CupfoxProvider/) | cupfox.in | 4 | JSON API `/tea/{vodId}-{epSlug}` |
| [OlevodProvider](OlevodProvider/) | olevod.com | 2 | `player_aaaa` JSON blob, Base64/URLdecode |
| [IyftvProvider](IyftvProvider/) | iyf.tv | 4 | HTML scrape, CORS header workaround |
| [Kuhh4joProvider](Kuhh4joProvider/) | kuhh4jo.com | 1 | Next.js + signed REST API |

---

## Provider Details

### CupfoxProvider — `cupfox.in` (v4)
- **Listing**: `GET /filter/?type={tv|movie|anime|show}&pg={page}` — HTML scrape, cards at `div[class*=movie-list-item]`
- **Detail**: `GET /vod-detail/{vodId}` — episode buttons are `span[class*=play-btn][ep_slug]`
- **Play URL**: `GET /tea/{vodId}-{epSlug}` → JSON `{video_plays:[{play_data, src_site}]}`
- **Data encoding**: `{vodId}:::{epSlug}`
- **Notes**: Use `www.cupfox.in` to avoid 301 redirect; episode slugs like `ep1`, `ep2`

### OlevodProvider — `olevod.com` (v2)
- **Listing**: `GET /index.php/vod/type/id/{1-4,14}.html` — page 2+ uses `/page/{n}.html` suffix
- **Detail**: `GET /index.php/vod/detail/id/{vodId}.html` — episodes via `a[href*=/vod/play/id/]`
- **Play URL**: `GET /index.php/vod/play/id/{vodId}/sid/{sid}/nid/{nid}.html` — parse `player_aaaa` JS object from HTML, fields: `url`, `encrypt` (0=plain, 1=base64, 2=urlencode), `flag`
- **Data encoding**: `{vodId}:::{sid}:::{nid}`

### DubokuProvider — `duboku.ru`
- **Play URL**: Parse `player_aaaa` JS object embedded in the play page HTML
- **Data encoding**: Similar `:::` separator pattern

### IyftvProvider — `iyf.tv` (v4)
- **Notes**: Requires `Origin` header workaround for CORS on API calls; HTML scraping

### Kuhh4joProvider — `kuhh4jo.com` (v1)
- **Stack**: Next.js App Router — all data comes from a signed REST API, not HTML scraping
- **API base**: `https://www.kuhh4jo.com/mw-movie/anonymous/...`
- **Signing** (reverse-engineered from JS chunk `2844`, module `49858`):
  ```
  dataStr = sorted_params.map("k=v").join("&")   // sorted alphabetically
  h       = dataStr + "&key=" + SIGN_KEY + "&t=" + timestamp_ms
  sign    = SHA1( MD5(h) )                         // both produce hex strings
  ```
  - `SIGN_KEY = "cb808529bae6b6be45ecfab29a4889bc"` (hardcoded in JS bundle)
  - `deviceId` = any fixed UUID (stored in localStorage by the browser, but arbitrary for API access)
  - Required headers per request: `sign`, `t`, `deviceId`, `authorization` (empty for anon), `client-type: 1`
- **Listing**: `GET /anonymous/video/list?pageNum={n}&pageSize=20&type1={type}`
  - `type1`: 1=电影, 2=电视剧, 3=综艺, 4=动漫, 88=短剧
  - Response: `{data: {list: [{vodId, vodName, vodPic, typeId1}], totalCount, totalPage}}`
- **Search**: `GET /anonymous/video/searchByWordPageable?keyword={q}&pageNum=1&pageSize=20`
- **Detail**: `GET /anonymous/video/detail?id={vodId}`
  - Response: `{data: {vodName, vodPic, vodContent, vodActor, vodClass, typeId1, episodeList: [{nid, name, sort}]}}`
- **Play URL**: `GET /anonymous/v2/video/episode/url?clientType=1&id={vodId}&nid={nid}`
  - Response: `{data: {list: [{url (m3u8), resolutionName, resolution (480/720/1080), needLogin, flag}]}}`
  - `needLogin: false` items are free; `needLogin: true` require an account
- **Data encoding**: `{vodId}:::{nid}`
- **Detail URL pattern**: `https://www.kuhh4jo.com/detail/{vodId}`

---

## Research Workflow for a New Site

1. **Fetch the homepage** with `curl -s -A "Mozilla/5.0" https://example.com` — find category URL patterns and card HTML structure.

2. **Identify the CMS/framework**:
   - `/_next/static/` → Next.js (client-rendered, data in RSC payloads or signed API)
   - `player_aaaa` in page source → MacCMS / similar PHP CMS
   - `/index.php/vod/` URL pattern → MacCMS
   - `/api.php/provide/vod/` → standard CMS XML/JSON API

3. **For HTML-scraped sites**: find card selectors, detail page episode list, and `player_aaaa` object in play pages.

4. **For Next.js/API sites**: download JS bundles and grep for `m3u8`, `/api/`, `sign`, `encrypt`, `playUrl`. Find the signing interceptor in `axios` setup.

5. **Test APIs with Python** using `urllib.request` before writing Kotlin — confirm signing works and response shape.

6. **Implement in Kotlin** following the existing provider pattern (`MainAPI()`, `getMainPage`, `search`, `load`, `loadLinks`).

---

## Common Patterns

### player_aaaa extraction (PHP CMS sites)
```kotlin
private fun extractPlayerAaaa(html: String): Pair<String, String>? {
    val markerIdx = html.indexOf("player_aaaa")
    val start = html.indexOf('{', markerIdx)
    // walk braces to find matching '}'
    val blob = html.substring(start, end + 1)
    val url = Regex(""""?url"?\s*:\s*"([^"]*)"""").find(blob)?.groupValues?.get(1)
    val encrypt = Regex(""""?encrypt"?\s*:\s*(\d+)""").find(blob)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return when (encrypt) {
        1 -> Base64.decode(url, Base64.DEFAULT).toString(Charsets.UTF_8)
        2 -> URLDecoder.decode(url, "UTF-8")
        else -> url
    } to (flag or "线路1")
}
```

### Signing for kuhh4jo.com
```kotlin
private fun makeSignHeaders(params: Map<String, Any>): Map<String, String> {
    val ts = System.currentTimeMillis()
    val dataStr = params.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
    val h = if (dataStr.isNotEmpty()) "$dataStr&key=$SIGN_KEY&t=$ts" else "key=$SIGN_KEY&t=$ts"
    val sign = sha1(md5(h))   // sha1(md5(h)) where both return lowercase hex
    return mapOf("sign" to sign, "t" to ts.toString(), "deviceId" to deviceId,
                 "authorization" to "", "client-type" to "1")
}
```

---

## .gitignore Notes

- `/com/`, `/androidx/`, `/app/`, `/coil3/`, `/go/`, `/io/`, `/okhttp/`, `/org/`, `/META-INF/` — extracted Gradle/Android dependency JARs that unpack at the repo root; patterns are **root-anchored** (`/com/` not `com/`) to avoid matching `src/main/kotlin/com/`.
- `.claude/settings.local.json` — machine-specific session permissions, excluded.
- `android-sdk/` — local Android SDK, excluded.
- `.vscode` — IDE settings, excluded (but `settings.json` was already tracked; revert any local-only additions before committing).
