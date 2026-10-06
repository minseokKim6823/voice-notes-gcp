param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ProjectId,
    [string]$BucketName,
    [string]$Location = 'us-central1'
)

$ErrorActionPreference = 'Stop'
if (-not $BucketName) { $BucketName = "$ProjectId-voice-notes-audio" }
$gcloud = (Get-Command gcloud.cmd -ErrorAction Stop).Source

& $gcloud auth print-access-token 1>$null
if ($LASTEXITCODE -ne 0) {
    throw 'GCP 로그인이 필요합니다. gcloud auth login을 실행하세요.'
}

& $gcloud services enable speech.googleapis.com storage.googleapis.com cloudresourcemanager.googleapis.com --project=$ProjectId
if ($LASTEXITCODE -ne 0) {
    throw 'GCP API 활성화에 실패했습니다. 프로젝트 권한과 결제 설정을 확인하세요.'
}

$projectBuckets = & $gcloud storage buckets list --project=$ProjectId --format='value(name)'
if ($LASTEXITCODE -ne 0) {
    throw '프로젝트 버킷 목록을 조회하지 못했습니다. Storage 권한을 확인하세요.'
}
$bucketNames = @($projectBuckets | ForEach-Object { $_.Trim().TrimEnd('/') -replace '^gs://', '' })
if ($bucketNames -notcontains $BucketName) {
    & $gcloud storage buckets create "gs://$BucketName" --project=$ProjectId --location=$Location --default-storage-class=STANDARD --uniform-bucket-level-access --public-access-prevention
    if ($LASTEXITCODE -ne 0) {
        throw '버킷 생성에 실패했습니다. 버킷 이름의 전역 중복 여부와 프로젝트 권한을 확인하세요.'
    }
}

$bucketMetadata = & $gcloud storage buckets describe "gs://$BucketName" --format='json(name,location,storageClass)'
if ($LASTEXITCODE -ne 0) {
    throw '버킷 확인에 실패했습니다.'
}
$bucket = ($bucketMetadata -join [Environment]::NewLine) | ConvertFrom-Json
if ($bucket.location -ine $Location) {
    throw "기존 버킷 위치 $($bucket.location)가 요청한 위치 $Location와 다릅니다."
}

$bucket | Select-Object name, location, storageClass | Format-Table -AutoSize

Write-Output "application-local.yml에 voice-notes.project-id=$ProjectId 및 voice-notes.bucket=$BucketName 값을 사용하세요."
