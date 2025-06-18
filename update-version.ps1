param(
    [Parameter(Mandatory=$true)][ValidateSet('fix','feat')]$Type,
    [Parameter(Mandatory=$true)][string]$Description
)

$pom = 'pom.xml'
$pluginyml = 'src/main/resources/plugin.yml'
$changelog = 'CHANGELOG.md'
$readme = 'README.md'

# Get current version from pom.xml
[xml]$pomXml = Get-Content $pom
$versionNode = $pomXml.project.version
$currentVersion = $versionNode.'#text'

# Parse version
$verParts = $currentVersion -split '\.'
$major = [int]$verParts[0]
$minor = [int]$verParts[1]
$patch = [int]$verParts[2]

if ($Type -eq 'fix') {
    $patch++
} elseif ($Type -eq 'feat') {
    $minor++
    $patch = 0
}
$newVersion = "$major.$minor.$patch"

# Update pom.xml
$versionNode.'#text' = $newVersion
$pomXml.Save($pom)

# Update plugin.yml
$pluginYmlText = Get-Content $pluginyml -Raw
$pluginYmlText = $pluginYmlText -replace '(?m)^version: .+$', "version: $newVersion"
Set-Content $pluginyml $pluginYmlText

# Update README.md badge
$readmeText = Get-Content $readme -Raw
$readmeText = $readmeText -replace '(Version-)[\d\.]+(-orange\?style=for-the-badge)', "$1$newVersion$2"
Set-Content $readme $readmeText

# Prepend to CHANGELOG.md
$date = Get-Date -Format 'yyyy-MM-dd'
if ($Type -eq 'fix') {
    $entry = "## [$newVersion] - $date`n### Fixed`n- $Description`n`n"
} else {
    $entry = "## [$newVersion] - $date`n### Added`n- $Description`n`n"
}
$changelogText = Get-Content $changelog -Raw
$changelogText = $changelogText -replace "(# Changelog\s*)", "$1$entry"
Set-Content $changelog $changelogText

Write-Host "Updated to version $newVersion in pom.xml, plugin.yml, README.md, and updated changelog."
