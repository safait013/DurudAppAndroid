# =====================================================================
# End-to-end test: first-launch language selection + persistence
# + language switching (Settings-mode picker).
#
# Prerequisites:
#   - Device or emulator connected (adb devices shows one entry)
#   - Debug APK installed:  adb install -r app\build\outputs\apk\debug\app-debug.apk
#
# Run:  powershell -ExecutionPolicy Bypass -File device_test.ps1
#       powershell -ExecutionPolicy Bypass -File device_test.ps1 -First ur -Switch bn
# All checks print PASS/FAIL markers; everything is ASCII-only.
# =====================================================================
param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$First = "ur",    # language picked on the first launch
    [string]$Switch = "bn"    # language switched to via the Settings-mode picker
)

if (-not (Test-Path $Adb)) { $Adb = "adb" }
$Pkg    = "com.primebytelabs.durood"
$Main   = "$Pkg/com.darood.app.MainActivity"
$Picker = "$Pkg/com.darood.app.LanguagePickerActivity"
$fails  = 0

function Ui {
    & $Adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
    & $Adb shell cat /sdcard/ui.xml 2>$null
}
function Wait-Ui { param($pattern)
    for ($i = 0; $i -lt 24; $i++) {
        $x = Ui
        if ($x -match $pattern) { return $x }
        Start-Sleep -Milliseconds 500
    }
    return Ui
}
function Tap-Button { param($uiXml, $id)
    $m = [regex]::Match($uiXml, 'resource-id="' + $Pkg + ':id/' + $id + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { throw "button not found: $id" }
    $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    & $Adb shell input tap $x $y
}
function Check { param($name, $ok)
    if ($ok) { Write-Output "PASS: $name" } else { Write-Output "FAIL: $name"; $script:fails++ }
}
function Expected-Dir { param($langCode)
    if ($langCode -eq "ur") { "rtl" } else { "ltr" }
}

Write-Output "=== 1. CLEAR APP DATA (fresh-install state) ==="
& $Adb shell pm clear $Pkg

Write-Output "=== 2. FIRST LAUNCH: language picker must appear ==="
& $Adb shell am start -n $Main | Out-Null
$ui = Wait-Ui 'btn_lang_en'
Check "picker shown on first launch" ($ui -match 'btn_lang_en')

Write-Output ("=== 3. SELECT FIRST-LAUNCH LANGUAGE: " + $First + " ===")
Tap-Button $ui ("btn_lang_" + $First)
Start-Sleep -Seconds 3

Write-Output "=== 4. PERSISTED PREFERENCE (shared_prefs/app_settings.xml) ==="
$prefs = & $Adb shell run-as $Pkg cat shared_prefs/app_settings.xml
$prefs
Check ("saved language is " + $First) ($prefs -match ('"app_language" > "' + $First + '"'))

Write-Output "=== 5. APPLIED LANGUAGE (logcat) ==="
$applied = & $Adb logcat -d -s DaroodApp:* 2>$null
$applied | Select-Object -Last 3
Check ("main UI rebuilt in " + $First + " dir=" + (Expected-Dir $First)) (($applied -join "`n") -match ('Applying UI language: ' + $First + ' dir=' + (Expected-Dir $First)))

Write-Output "=== 6. APP RESTART: picker must NOT reappear; language persists ==="
& $Adb shell am force-stop $Pkg
& $Adb logcat -c 2>$null
& $Adb shell am start -n $Main | Out-Null
$ui2 = Wait-Ui 'android.webkit.WebView|btn_lang_en'
Check "picker NOT shown after restart" (-not ($ui2 -match 'btn_lang_en'))
Check "main WebView shown after restart" ($ui2 -match 'android.webkit.WebView')
$applied2 = & $Adb logcat -d -s DaroodApp:* 2>$null
$applied2 | Select-Object -Last 2
Check ("persisted language used after restart (" + $First + " dir=" + (Expected-Dir $First) + ")") (($applied2 -join "`n") -match ('Applying UI language: ' + $First + ' dir=' + (Expected-Dir $First)))

Write-Output ("=== 7. SWITCH LANGUAGE via Settings-mode picker to " + $Switch + " ===")
& $Adb logcat -c 2>$null
& $Adb shell am start -n $Picker --ez from_settings true | Out-Null
$ui3 = Wait-Ui 'btn_lang_en'
Tap-Button $ui3 ("btn_lang_" + $Switch)
Start-Sleep -Seconds 4

Write-Output "=== 8. PREFERENCE + APPLIED LANGUAGE AFTER SWITCH ==="
$prefs2 = & $Adb shell run-as $Pkg cat shared_prefs/app_settings.xml
$prefs2
Check ("saved language updated to " + $Switch) ($prefs2 -match ('"app_language" > "' + $Switch + '"'))
$applied3 = & $Adb logcat -d -s DaroodApp:* 2>$null
$applied3 | Select-Object -Last 3
Check ("UI rebuilt in " + $Switch + " dir=" + (Expected-Dir $Switch) + " after switch") (($applied3 -join "`n") -match ('Applying UI language: ' + $Switch + ' dir=' + (Expected-Dir $Switch)))

Write-Output "=== 9. SWITCH BACK TO ENGLISH (must stay LTR) ==="
& $Adb logcat -c 2>$null
& $Adb shell am start -n $Picker --ez from_settings true | Out-Null
$ui4 = Wait-Ui 'btn_lang_en'
Tap-Button $ui4 'btn_lang_en'
Start-Sleep -Seconds 4
$prefs3 = & $Adb shell run-as $Pkg cat shared_prefs/app_settings.xml
$prefs3
Check "saved language updated to en" ($prefs3 -match '"app_language" > "en"')
$applied4 = & $Adb logcat -d -s DaroodApp:* 2>$null
$applied4 | Select-Object -Last 3
Check "UI rebuilt in en dir=ltr" (($applied4 -join "`n") -match 'Applying UI language: en dir=ltr')

Write-Output "=== RESULT ==="
if ($fails -eq 0) { Write-Output "ALL CHECKS PASSED" } else { Write-Output "$fails CHECK(S) FAILED" }
exit $fails