param (
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Target
)

$rootDir = $PSScriptRoot

# Normalize target path
$targetPath = $Target
if (-not ($targetPath.StartsWith("src\")) -and -not ($targetPath.StartsWith("src/"))) {
    $found = Get-ChildItem -Path "$rootDir\src" -Directory -Recurse -Depth 1 | Where-Object { $_.Name -eq $Target } | Select-Object -First 1
    if ($found) {
        $targetPath = $found.FullName.Substring($rootDir.Length + 1).Replace("\", "/")
    } else {
        Write-Host "Could not find extension directory for: $Target" -ForegroundColor Red
        exit 1
    }
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "Comparing $targetPath against upstream/main" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

git diff --stat upstream/main -- "$targetPath"
Write-Host ""
git diff upstream/main -- "$targetPath"
