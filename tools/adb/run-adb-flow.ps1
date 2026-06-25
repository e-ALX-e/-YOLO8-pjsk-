param(
    [string]$Steps = (Join-Path $PSScriptRoot "steps.example.json"),
    [string]$Serial,
    [string]$Adb = "adb",
    [int]$Loop = 1,
    [switch]$DryRun,
    [switch]$ListDevices
)

$ErrorActionPreference = "Stop"

function Format-Args {
    param([string[]]$Arguments)
    return ($Arguments | ForEach-Object {
        if ($_ -match "\s") { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }) -join " "
}

function Invoke-Adb {
    param(
        [string[]]$Arguments,
        [switch]$ReturnOutput
    )

    $fullArgs = @()
    if ($Serial) {
        $fullArgs += @("-s", $Serial)
    }
    $fullArgs += $Arguments

    if ($DryRun) {
        Write-Host "[dry-run] $Adb $(Format-Args $fullArgs)"
        return $null
    }

    if ($ReturnOutput) {
        $output = & $Adb @fullArgs
        if ($LASTEXITCODE -ne 0) {
            throw "adb command failed: $Adb $(Format-Args $fullArgs)"
        }
        return $output
    }

    & $Adb @fullArgs | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: $Adb $(Format-Args $fullArgs)"
    }
}

function Get-Required {
    param(
        [object]$Object,
        [string]$Name
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        throw "step is missing required field: $Name"
    }
    return $property.Value
}

function Get-Optional {
    param(
        [object]$Object,
        [string]$Name,
        [object]$DefaultValue = $null
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        return $DefaultValue
    }
    return $property.Value
}

function Convert-ToAdbText {
    param([string]$Value)

    if ($Value -match "[^A-Za-z0-9 _.,@:/+-]") {
        Write-Warning "adb input text has limited support for special characters. Consider pasting text manually if this step fails."
    }

    return $Value.Replace(" ", "%s")
}

function Assert-AdbAvailable {
    $command = Get-Command $Adb -ErrorAction SilentlyContinue
    if ($null -eq $command -and -not (Test-Path -LiteralPath $Adb)) {
        throw "adb was not found. Add Android platform-tools to PATH or pass -Adb with the full adb path."
    }
}

function Assert-DeviceReady {
    if ($DryRun) {
        return
    }

    $lines = & $Adb devices
    if ($LASTEXITCODE -ne 0) {
        throw "failed to list adb devices"
    }

    $devices = @()
    foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device$") {
            $devices += $Matches[1]
        }
    }

    if ($Serial) {
        if ($devices -notcontains $Serial) {
            throw "device '$Serial' is not connected or not authorized"
        }
        return
    }

    if ($devices.Count -eq 0) {
        throw "no authorized adb device found. Check USB debugging and run: adb devices"
    }

    if ($devices.Count -gt 1) {
        throw "multiple devices found. Re-run with -Serial <device-id>"
    }
}

function Invoke-Step {
    param(
        [object]$Step,
        [int]$Index
    )

    $action = [string](Get-Required $Step "action")
    $lowerAction = $action.ToLowerInvariant()

    switch ($lowerAction) {
        "note" {
            $message = [string](Get-Optional $Step "message" "")
            if ($message) {
                Write-Host "[$Index] $message"
            }
        }

        "wait" {
            $ms = [int](Get-Required $Step "ms")
            Write-Host "[$Index] wait ${ms}ms"
            if (-not $DryRun) {
                Start-Sleep -Milliseconds $ms
            }
        }

        "tap" {
            $x = [int](Get-Required $Step "x")
            $y = [int](Get-Required $Step "y")
            Write-Host "[$Index] tap $x,$y"
            Invoke-Adb @("shell", "input", "tap", [string]$x, [string]$y)
        }

        "longtap" {
            $x = [int](Get-Required $Step "x")
            $y = [int](Get-Required $Step "y")
            $durationMs = [int](Get-Optional $Step "durationMs" 600)
            Write-Host "[$Index] long tap $x,$y for ${durationMs}ms"
            Invoke-Adb @("shell", "input", "swipe", [string]$x, [string]$y, [string]$x, [string]$y, [string]$durationMs)
        }

        "swipe" {
            $x1 = [int](Get-Required $Step "x1")
            $y1 = [int](Get-Required $Step "y1")
            $x2 = [int](Get-Required $Step "x2")
            $y2 = [int](Get-Required $Step "y2")
            $durationMs = [int](Get-Optional $Step "durationMs" 300)
            Write-Host "[$Index] swipe $x1,$y1 -> $x2,$y2 for ${durationMs}ms"
            Invoke-Adb @("shell", "input", "swipe", [string]$x1, [string]$y1, [string]$x2, [string]$y2, [string]$durationMs)
        }

        "text" {
            $value = [string](Get-Required $Step "value")
            Write-Host "[$Index] text"
            Invoke-Adb @("shell", "input", "text", (Convert-ToAdbText $value))
        }

        "key" {
            $code = [string](Get-Required $Step "code")
            Write-Host "[$Index] key $code"
            Invoke-Adb @("shell", "input", "keyevent", $code)
        }

        "back" {
            Write-Host "[$Index] back"
            Invoke-Adb @("shell", "input", "keyevent", "BACK")
        }

        "home" {
            Write-Host "[$Index] home"
            Invoke-Adb @("shell", "input", "keyevent", "HOME")
        }

        "launch" {
            $package = [string](Get-Required $Step "package")
            $activity = [string](Get-Optional $Step "activity" "")
            if ($activity) {
                Write-Host "[$Index] launch $package/$activity"
                Invoke-Adb @("shell", "am", "start", "-n", "$package/$activity")
            } else {
                Write-Host "[$Index] launch $package"
                Invoke-Adb @("shell", "monkey", "-p", $package, "-c", "android.intent.category.LAUNCHER", "1")
            }
        }

        "screenshot" {
            $path = [string](Get-Optional $Step "path" (Join-Path (Get-Location) "adb-screenshot.png"))
            $fullPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($path)
            $parent = Split-Path -Parent $fullPath
            if (-not $DryRun -and $parent -and -not (Test-Path -LiteralPath $parent)) {
                New-Item -ItemType Directory -Force -Path $parent | Out-Null
            }

            $stamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
            $remotePath = "/sdcard/Download/adb-flow-$stamp.png"

            Write-Host "[$Index] screenshot $fullPath"
            Invoke-Adb @("shell", "screencap", "-p", $remotePath)
            Invoke-Adb @("pull", $remotePath, $fullPath)
            Invoke-Adb @("shell", "rm", $remotePath)
        }

        default {
            throw "unknown action '$action' at step $Index"
        }
    }
}

if (-not $DryRun -or $ListDevices) {
    Assert-AdbAvailable
}

if ($ListDevices) {
    & $Adb devices
    exit $LASTEXITCODE
}

if (-not (Test-Path -LiteralPath $Steps)) {
    throw "steps file not found: $Steps"
}

Assert-DeviceReady

$flow = Get-Content -Raw -LiteralPath $Steps | ConvertFrom-Json
if ($flow -is [array]) {
    $stepsList = $flow
} else {
    $stepsList = $flow.steps
    if (-not $PSBoundParameters.ContainsKey("Loop")) {
        $flowLoop = Get-Optional $flow "loop" $Loop
        $Loop = [int]$flowLoop
    }
}

if ($null -eq $stepsList -or $stepsList.Count -eq 0) {
    throw "steps file does not contain any steps"
}

if ($Loop -lt 1) {
    throw "-Loop must be 1 or greater"
}

for ($run = 1; $run -le $Loop; $run++) {
    if ($Loop -gt 1) {
        Write-Host "run $run/$Loop"
    }

    for ($i = 0; $i -lt $stepsList.Count; $i++) {
        Invoke-Step -Step $stepsList[$i] -Index ($i + 1)
    }
}

Write-Host "done"
