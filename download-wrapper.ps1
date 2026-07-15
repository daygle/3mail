$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$url = 'https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar'
$dest = 'C:\Users\glen\StudioProjects\3mail\gradle\wrapper\gradle-wrapper.jar'
try {
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
    $info = Get-Item $dest
    Write-Host ("Downloaded gradle-wrapper.jar {0} bytes" -f $info.Length)
} catch {
    Write-Host ("Download FAILED: {0}" -f $_.Exception.Message)
    exit 1
}
