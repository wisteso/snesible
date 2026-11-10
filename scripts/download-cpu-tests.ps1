<#
.SYNOPSIS
    Downloads the SingleStepTests 65816 suite into testdata/65816 (git-ignored).

.DESCRIPTION
    The suite has one JSON file per opcode and CPU mode: "00.e.json" (emulation mode) through
    "ff.n.json" (native mode), 512 files with 10,000 tests each. GitHub serves them
    gzip-compressed when asked, so we store the compressed bytes as-is (*.json.gz). That's about
    455 MB instead of 3.3 GB. The test harness reads them through a GZIPInputStream.

    Files that already exist are skipped, so the script can be re-run to finish an interrupted
    download.

    Source: https://github.com/SingleStepTests/65816 (MIT licence)
#>
$ErrorActionPreference = 'Stop'

$baseUrl = 'https://raw.githubusercontent.com/SingleStepTests/65816/main/v1'
$destination = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\testdata\65816'))
New-Item -ItemType Directory -Force $destination | Out-Null

# curl reads URL/output pairs from a config file, which avoids Windows' command-line length limit.
$config = New-TemporaryFile
$missing = 0
foreach ($opcode in 0..255) {
    foreach ($mode in 'e', 'n') {
        $name = '{0:x2}.{1}.json' -f $opcode, $mode
        $target = Join-Path $destination "$name.gz"
        if (-not (Test-Path $target)) {
            Add-Content -Encoding ascii $config "url = `"$baseUrl/$name`""
            # curl treats backslashes in config files as escape characters, so use forward slashes.
            $output = $target.Replace('\', '/')
            Add-Content -Encoding ascii $config "output = `"$output`""
            $missing++
        }
    }
}

if ($missing -eq 0) {
    Write-Host 'All 512 test files are already present.'
} else {
    Write-Host "Downloading $missing test files to $destination ..."
    # The Accept-Encoding header without --compressed makes curl save the gzip bytes untouched.
    & curl.exe --fail --silent --show-error --parallel --parallel-max 8 `
        -H 'Accept-Encoding: gzip' -K $config.FullName
    if ($LASTEXITCODE -ne 0) { throw "curl failed with exit code $LASTEXITCODE" }
    Write-Host 'Done.'
}
Remove-Item $config
