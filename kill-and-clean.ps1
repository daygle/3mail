$ErrorActionPreference = 'SilentlyContinue'
Get-Process -Name gradle -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host ("killing gradle pid=" + $_.Id)
    $_.Kill()
}
Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object {
    $_.MainWindowTitle -match 'Gradle|gradle' -or $_.CommandLine -match 'gradle'
} | ForEach-Object {
    Write-Host ("killing gradle-java pid=" + $_.Id)
    $_.Kill()
}
Start-Sleep -Seconds 3
Remove-Item -Recurse -Force 'C:\Users\glen\StudioProjects\3mail\app\build' -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force 'C:\Users\glen\StudioProjects\3mail\build' -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force 'C:\Users\glen\StudioProjects\3mail\.gradle' -ErrorAction SilentlyContinue
Write-Host 'cleaned'
Get-ChildItem 'C:\Users\glen\StudioProjects\3mail\app\build' -ErrorAction SilentlyContinue | Measure-Object | Select-Object -ExpandProperty Count
