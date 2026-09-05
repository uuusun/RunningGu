[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Pbf,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')]
    [string]$PbfDate,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$PbfSha256,

    [Parameter(Mandatory = $true)]
    [string]$WorkDirectory,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CreatedBy,

    [ValidatePattern('^[0-9]+[kKmMgG]$')]
    [string]$ImportXms = '1g',

    [ValidatePattern('^[0-9]+[kKmMgG]$')]
    [string]$ImportXmx = '8g',

    [string]$BuilderImage = 'runninggu-graphhopper-builder:11.0'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker 명령이 실패했습니다. exit_code=$LASTEXITCODE"
    }
}

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptDirectory '..\..\..')).Path
$pbfPath = (Resolve-Path -LiteralPath $Pbf).Path
if (-not (Test-Path -LiteralPath $pbfPath -PathType Leaf)) {
    throw "PBF 파일이 없습니다: $Pbf"
}

$workParentInput = Split-Path -Parent $WorkDirectory
if ([string]::IsNullOrWhiteSpace($workParentInput)) {
    $workParentInput = '.'
}
New-Item -ItemType Directory -Path $workParentInput -Force | Out-Null
$workParent = (Resolve-Path -LiteralPath $workParentInput).Path
$workPath = Join-Path $workParent (Split-Path -Leaf $WorkDirectory)
if (Test-Path -LiteralPath $workPath) {
    throw "work directory가 이미 존재합니다: $workPath"
}

New-Item -ItemType Directory -Path (Join-Path $workPath 'output') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $workPath 'srtm-cache') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $workPath 'artifacts') | Out-Null

Invoke-Docker @(
    'buildx', 'build',
    '--load',
    '--platform', 'linux/amd64',
    '--provenance=false',
    '--sbom=false',
    '--build-arg', 'SOURCE_DATE_EPOCH=0',
    '--tag', $BuilderImage,
    '--file', (Join-Path $scriptDirectory 'Dockerfile'),
    $repositoryRoot
)

$builderDigest = (& docker image inspect --format '{{.Id}}' $BuilderImage).Trim()
if ($LASTEXITCODE -ne 0 -or $builderDigest -notmatch '^sha256:[0-9a-f]{64}$') {
    throw 'builder image digest를 얻지 못했습니다.'
}

$pbfDirectory = Split-Path -Parent $pbfPath
$pbfFileName = Split-Path -Leaf $pbfPath
Invoke-Docker @(
    'run', '--rm',
    '--platform', 'linux/amd64',
    '--env', 'HOME=/tmp',
    '--env', "PBF_FILE_NAME=$pbfFileName",
    '--env', "PBF_DATE=$PbfDate",
    '--env', "PBF_SHA256=$PbfSha256",
    '--env', "BUILDER_IMAGE_DIGEST=$builderDigest",
    '--env', "CREATED_BY=$CreatedBy",
    '--env', "IMPORT_XMS=$ImportXms",
    '--env', "IMPORT_XMX=$ImportXmx",
    '--mount', "type=bind,source=$pbfDirectory,target=/work/input,readonly",
    '--mount', "type=bind,source=$(Join-Path $workPath 'output'),target=/work/output",
    '--mount', "type=bind,source=$(Join-Path $workPath 'srtm-cache'),target=/work/srtm-cache",
    '--mount', "type=bind,source=$(Join-Path $workPath 'artifacts'),target=/work/artifacts",
    $BuilderImage
)

Write-Output "builder_image_digest=$builderDigest"
Write-Output "artifact_root=$(Join-Path $workPath 'artifacts')"
