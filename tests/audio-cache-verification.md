Supabase per-file pronunciation cache — implementation and verification

The inspected workspace had no Supabase downloader or previous manifest model: DuroodAudioPlayer still referenced deleted pronunciation resources. This change adds the required manifest/cache transport to that same player. It does not create a second playback controller. The live public manifest was fetched read-only and confirmed to contain manifestVersion 1, 25 Durood entries and 15 Salam entries with per-file versions.

1. **Manifest models:** AudioManifest and its Item model parse manifestVersion, HTTPS baseUrl, category, numeric ID, safe basename and positive per-file version. IDs outside 1–25/1–15 and malformed entries are unavailable. All occurrences of a duplicate ID are unavailable, rather than choosing a duplicate. Missing IDs never generate guessed URLs.

2. **Files changed:** new `app/src/main/java/com/darood/app/AudioManifest.java`, `AudioCache.java`, `AudioDownloads.java`; updated `DuroodAudioPlayer.java`; four new native error strings in each of `res/values/strings.xml`, `values-bn/strings.xml`, and `values-ur/strings.xml`. New targeted tests: `tests/AudioCacheTest.java`, `audio-cache.test.ps1`, `PronunciationCachePlayerTest.java`, `pronunciation-cache-player.test.ps1`, and this report. Existing unrelated work and deleted raw recordings were left as found. The old raw-resource playback test fixtures are reused as doubles by the new cache-player test, not treated as tests of the new download source.

3. **Per-file versioning:** a requested item is current only when its file exists, has nonzero size, matches the saved SHA-256, and both saved per-file version and manifest filename match. Only that requested item's mismatch triggers its download.

4. **manifestVersion:** validated, logged and retained inside the cached manifest as the document revision. It is not included in audio metadata validity comparisons.

5. **No global invalidation:** tests explicitly change manifestVersion while keeping file versions unchanged and verify zero audio downloads. Updating Durood 1 leaves Durood 2 and Salam 15 untouched; updating Salam 15 leaves Durood cache untouched. The same keyed logic handles Durood 12/13 and all remaining IDs.

6. **Metadata:** existing app_settings SharedPreferences stores one atomic JSON record per `audio_version_<category>_<id>`, containing `version`, manifest `file`, private generation basename `local`, and `sha256`. No Room table/migration. Metadata is committed only after complete audio validation and successful rename. A failed SharedPreferences commit restores its previous in-memory value too.

7. **Storage:** `context.getFilesDir()/audio/durood/` and `/audio/salam/`. Each downloaded generation uses `<id>_<uuid>_<manifest-file>`, referenced by its item's metadata. Immutable generation names allow the old valid file and metadata to remain intact until a successful transaction. The small manifest cache is `files/audio/audio_manifest.json`, wrapping the last successful manifest plus `fetchedAt`.

8. **URL construction:** `baseUrl + '/' + category + '/' + item.file`, using the supplied basename. Audio GETs append `?audioVersion=<item.version>` to avoid an older CDN response at an unchanged filename. Manifest and audio GETs also request no-cache. No list of 40 hardcoded URLs or filePattern logic. HTTPS, no credentials/query/fragment in baseUrl, and safe basename checks are enforced. Paths containing slash/backslash, traversal, or encoded path characters are rejected.

9. **First download:** a pronunciation request runs off the UI thread, obtains a usable manifest, resolves the exact category/ID, checks connectivity and free space, downloads to a temporary private file, verifies complete length/nonzero bytes, validates an audio track, computes SHA-256, renames, persists metadata, and returns the local file to the existing player.

10. **Cache hit:** a successful manifest is fresh for two hours; it is not requested during UI rendering or on every Play. Current verified audio returns directly without an audio GET. The repository uses two short-lived workers so cached B can resolve while A downloads; this is tested. Stale manifests refresh on a worker before the cache decision. Failed refresh attempts are throttled for one minute and preserve the last usable manifest.

11. **Single-file update:** a differing item version or filename downloads only that item into a new generation. The old generation is removed only after the replacement is validated and the new metadata commits. Same-version filename changes also trigger replacement.

12. **Offline current cache:** a file valid against the last successful manifest plays locally, including after process recreation and when the manifest refresh timestamp is old. A failed remote manifest request also falls back to that cached manifest without deleting good audio.

13. **Offline stale/missing cache:** known-stale or never-downloaded audio is not played and no version is advanced. The existing-language no-internet message is shown. Missing/duplicate/invalid manifest entries show the localized audio-unavailable message. HTTP/transfer failures show the localized download-failed message.

14. **Storage handling:** Content-Length plus 2 MiB safety margin is checked where available; unknown-length downloads receive an initial allowance and repeated free-space checks. Mid-transfer ENOSPC and metadata/storage failures produce the localized storage message. Transfers are bounded to 64 MiB and have connect/read/overall time limits. Empty, truncated and non-audio responses are rejected.

15. **Atomicity:** bytes are written only to a unique `.tmp` file, flushed/synced, closed and validated. Android's same-filesystem `Os.rename` promotes it to an immutable generation; the item metadata is then committed. Rename, transfer, validation or metadata failure removes the incomplete/new generation and preserves the previous committed file and version. Runtime failure paths clean temporary files. An abrupt process/power loss can leave an unreferenced temporary/generation file; it cannot make old metadata claim the newer version was downloaded.

16. **Concurrency:** AudioDownloads coalesces in-flight work by category/ID. Repeated pending taps are ignored by the player; subscribers to the same download share one operation. Different items cannot overwrite each other's files. Player request generations and callback cancellation ensure Stop, Activity pause/destruction and the latest requested item win over late downloads. One MediaPlayer still owns playback, completion, errors and switching in both directions.

17. **No raw pronunciation dependency:** DuroodAudioPlayer now receives only validated local cache paths; there are no R.raw.durood<number> or R.raw.salam references in production Java. Removed recordings were not recreated. The separate existing reminder WAV and its alarm controller remain unchanged.

18. **Scope:** no content, Arabic/meaning/reference/title/order/ID changes; no card/button/style or HTML changes; no Read/Copy/Share/My Activity/Room/Tasbih/Settings/Hijri/notification/Friday reminder/font/theme/About/version changes. INTERNET and ACCESS_NETWORK_STATE were already declared, so no manifest or permission changes were needed. No storage permission or direct Supabase playback streaming. No Git commit, push, reset, revert, clean or stash performed.

19. **Debug:** assembleDebug passed. Artifact: `app/build/outputs/apk/debug/app-debug.apk`. Build log: `build/audio-cache-final-build.log`. 42 manifest/cache/coalescing checks passed against real temporary files with deterministic HTTP/storage/Android doubles; 183 cache-player lifecycle checks passed using the actual DuroodAudioPlayer with Android/download doubles.

20. **Release:** assembleRelease and its vital lint passed. Artifact: `app/build/outputs/apk/release/app-release-unsigned.apk`. Signing and Gradle configuration were unchanged. Existing deprecation warnings were not expanded into unrelated fixes.

21. **Limits of verification:** the live manifest shape was verified read-only. HTTP/storage failures, version updates, checksum corruption, atomic rollback, duplicate prevention, offline recreation, cached-play responsiveness and player lifecycle were exercised in automated tests; the remote manifest/audio objects were not modified to run them. No physical Android speaker/decoder/network-transition test is claimed. Device testing should cover real Supabase download/play, airplane-mode cache playback, a version bump on a test server, low storage, rapid switching and Activity background/recreation.
