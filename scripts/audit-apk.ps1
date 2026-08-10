param(
    [string]$Apk = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Apk)) {
    $Apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
}
$Apk = (Resolve-Path -LiteralPath $Apk).Path

$sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
if (-not $sdkRoot) {
    throw 'ANDROID_SDK_ROOT or ANDROID_HOME is required.'
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if (-not $buildTools) {
    throw "Android build-tools not found below $sdkRoot"
}

$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$dexdump = Join-Path $buildTools.FullName 'dexdump.exe'
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'

& $apksigner verify --verbose --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
& $zipalign -c -P 16 4 $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK zip alignment verification failed.' }

$badging = & $aapt dump badging $Apk
if ($LASTEXITCODE -ne 0) { throw 'aapt badging failed.' }
$badgingText = $badging -join "`n"
if ($badgingText -notmatch "package: name='dev\.a2dprouter\.system'") {
    throw 'Unexpected application id.'
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("GlobalVoipA2dp-audit-" + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($tempRoot) | Out-Null
$archive = $null
try {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Apk)
    $required = @(
        'META-INF/xposed/java_init.list',
        'META-INF/xposed/module.prop',
        'META-INF/xposed/scope.list',
        'classes.dex'
    )
    foreach ($relative in $required) {
        if (-not $archive.GetEntry($relative)) {
            throw "Missing required APK entry: $relative"
        }
    }
    if ($archive.GetEntry('assets/xposed_init')) {
        throw 'Legacy Xposed entry found in Modern-only module.'
    }

    function Read-ZipText([System.IO.Compression.ZipArchiveEntry]$ZipEntry) {
        $stream = $ZipEntry.Open()
        try {
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true, 4096, $true)
            try {
                return $reader.ReadToEnd()
            } finally {
                $reader.Dispose()
            }
        } finally {
            $stream.Dispose()
        }
    }

    $entry = (Read-ZipText ($archive.GetEntry('META-INF/xposed/java_init.list'))).Trim()
    $scope = (Read-ZipText ($archive.GetEntry('META-INF/xposed/scope.list'))).Trim()
    $properties = Read-ZipText ($archive.GetEntry('META-INF/xposed/module.prop'))
    if ($entry -ne 'dev.a2dprouter.system.GlobalVoipA2dpModule') { throw 'Unexpected Modern entry point.' }
    if ($scope -ne 'system') { throw "Unsafe scope: $scope" }
    foreach ($expected in @('minApiVersion=102', 'targetApiVersion=102', 'staticScope=true', 'exceptionMode=protective', 'autoHotReload=false')) {
        if ($properties -notmatch [regex]::Escape($expected)) { throw "Missing module property: $expected" }
    }

    $dexEntries = @($archive.Entries | Where-Object { $_.FullName -match '^classes[0-9]*\.dex$' })
    for ($index = 0; $index -lt $dexEntries.Count; $index++) {
        $dexEntry = $dexEntries[$index]
        $dexPath = Join-Path $tempRoot ("classes-$index.dex")
        $input = $dexEntry.Open()
        try {
            $output = [IO.File]::Create($dexPath)
            try {
                $input.CopyTo($output)
            } finally {
                $output.Dispose()
            }
        } finally {
            $input.Dispose()
        }

        $ascii = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($dexPath))
        foreach ($forbidden in @('de.robv.android.xposed')) {
            if ($ascii.IndexOf($forbidden, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw "Legacy Xposed string found in $($dexEntry.FullName): $forbidden"
            }
        }
        $dump = & $dexdump $dexPath
        if ($LASTEXITCODE -ne 0) { throw "dexdump failed for $($dexEntry.FullName)" }
        if ($dump -match "Class descriptor\s+: 'Lio/github/libxposed/api/") {
            throw "compileOnly libxposed API classes were packaged into $($dexEntry.FullName)"
        }
    }
} finally {
    if ($archive) {
        $archive.Dispose()
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}

$hash = Get-FileHash -LiteralPath $Apk -Algorithm SHA256
[pscustomobject]@{
    Apk = $Apk
    Bytes = (Get-Item -LiteralPath $Apk).Length
    SHA256 = $hash.Hash
    Package = 'dev.a2dprouter.system'
    Scope = 'system'
    ModernApi = 102
}
