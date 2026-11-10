<#
.SYNOPSIS
    Downloads the SingleStepTests CPU suites into testdata/ (git-ignored).

.DESCRIPTION
    Two suites, both one JSON file per opcode:

      testdata/65816   the main CPU: "00.e.json" (emulation mode) to "ff.n.json" (native mode),
                       512 files with 10,000 tests each
      testdata/spc700  the sound CPU: "00.json" to "ff.json", 256 files with 1,000 tests each

    GitHub serves the files gzip-compressed when asked, so we store the compressed bytes as-is
    (*.json.gz): about 470 MB in total instead of 3.4 GB. The test harnesses read them through
    a GZIPInputStream.

    Files that already exist are skipped, so the script can be re-run to finish an interrupted
    download.

    Sources (MIT licence): https://github.com/SingleStepTests/65816
                           https://github.com/SingleStepTests/spc700
#>
$ErrorActionPreference = 'Stop'

$suites = @(
    @{ Name = '65816';  Url = 'https://raw.githubusercontent.com/SingleStepTests/65816/main/v1';  Modes = @('.e', '.n') },
    @{ Name = 'spc700'; Url = 'https://raw.githubusercontent.com/SingleStepTests/spc700/main/v1'; Modes = @('') }
)

# curl reads URL/output pairs from a config file, which avoids Windows' command-line length limit.
$config = New-TemporaryFile
$missing = 0
foreach ($suite in $suites) {
    $destination = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\testdata\$($suite.Name)"))
    New-Item -ItemType Directory -Force $destination | Out-Null
    foreach ($opcode in 0..255) {
        foreach ($mode in $suite.Modes) {
            $name = '{0:x2}{1}.json' -f $opcode, $mode
            $target = Join-Path $destination "$name.gz"
            if (-not (Test-Path $target)) {
                Add-Content -Encoding ascii $config "url = `"$($suite.Url)/$name`""
                # curl treats backslashes in config files as escape characters: use forward slashes.
                $output = $target.Replace('\', '/')
                Add-Content -Encoding ascii $config "output = `"$output`""
                $missing++
            }
        }
    }
}

if ($missing -eq 0) {
    Write-Host 'All test files are already present.'
} else {
    Write-Host "Downloading $missing test files ..."
    # The Accept-Encoding header without --compressed makes curl save the gzip bytes untouched.
    & curl.exe --fail --silent --show-error --parallel --parallel-max 8 `
        -H 'Accept-Encoding: gzip' -K $config.FullName
    if ($LASTEXITCODE -ne 0) { throw "curl failed with exit code $LASTEXITCODE" }
    Write-Host 'Done.'
}
Remove-Item $config
