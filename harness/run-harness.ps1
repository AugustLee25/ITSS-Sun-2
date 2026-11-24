<#
.SYNOPSIS
  Harness nghiệm thu Solution 2 (HANARE): chạy kiểm tra tự động, đối chiếu với ma trận yêu cầu, xuất báo cáo.

.DESCRIPTION
  Một vòng (iteration) = static -> be -> fe -> đánh giá requirements.json -> ghi reports\latest.md|json, NEXT_ACTIONS.md,
  history.log. Mã thoát 0 chỉ khi MỌI yêu cầu PASS và các bộ test BE/FE xanh.

  Quy trình vòng lặp (người/agent sửa giữa các vòng):
     1) .\run-harness.ps1            -> đọc reports\NEXT_ACTIONS.md
     2) sửa code/test cho các yêu cầu đang FAIL / MISSING
     3) chạy lại (có thể thu hẹp: -Stages static,fe  hoặc  -BeTests Solution2AcceptanceTest)
     4) lặp tới khi "ALL GREEN" (chạy lần cuối ĐẦY ĐỦ, không thu hẹp)

  Tùy chọn -FixCommand: lệnh chạy sau mỗi vòng còn đỏ (ví dụ gọi một agent sửa lỗi), nhận đường dẫn NEXT_ACTIONS.md
  qua biến môi trường HARNESS_NEXT_ACTIONS. Cùng -MaxIterations để tự lặp.
#>
[CmdletBinding()]
param(
  [string[]]$Stages = @('static', 'be', 'fe'),
  [string]$BeTests = '',
  [int]$MaxIterations = 1,
  [string]$FixCommand = '',
  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
# `powershell -File ... -Stages static,be` truyền "static,be" thành MỘT chuỗi: tách lại theo dấu phẩy.
$Stages = @($Stages | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$known = @('static', 'be', 'fe')
foreach ($s in $Stages) { if ($known -notcontains $s) { throw "Tầng không hợp lệ: '$s' (hợp lệ: $($known -join ', '))" } }
$Harness = $PSScriptRoot
$Root = Split-Path -Parent $Harness
$BE = Join-Path $Root 'BE'
$FE = Join-Path $Root 'FE'
$Reports = Join-Path $Harness 'reports'
New-Item -ItemType Directory -Force -Path $Reports | Out-Null

function Write-Utf8([string]$path, [string]$text) {
  [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-Logged([string]$workDir, [string]$command, [string]$logFile) {
  # chạy qua cmd để gom stdout+stderr vào một file log; trả về mã thoát
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = 'cmd.exe'
  $psi.Arguments = '/c ' + $command + ' > "' + $logFile + '" 2>&1'
  $psi.WorkingDirectory = $workDir
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $p = [System.Diagnostics.Process]::Start($psi)
  $p.WaitForExit()
  return $p.ExitCode
}

# ------------------------------------------------------------------ tầng chạy test
function Run-Be {
  $log = Join-Path $Reports 'be.log'
  $cmd = '.\mvnw.cmd -B test'
  if ($BeTests) { $cmd += ' -Dtest=' + $BeTests + ' -Dsurefire.failIfNoSpecifiedTests=false' }
  Write-Host "[be] $cmd" -ForegroundColor Cyan
  $code = Invoke-Logged $BE $cmd $log
  return @{ ran = $true; exit = $code; log = $log; partial = [bool]$BeTests }
}

function Run-Fe {
  $res = @{ ran = $true }
  $log = Join-Path $Reports 'fe-lint.log'
  Write-Host '[fe] npm run lint' -ForegroundColor Cyan
  $res.lint = Invoke-Logged $FE 'npm run lint' $log
  $json = Join-Path $Reports 'vitest.json'
  if (Test-Path $json) { Remove-Item $json -Force }
  Write-Host '[fe] vitest run' -ForegroundColor Cyan
  $res.test = Invoke-Logged $FE ('npx vitest run --reporter=json --outputFile="' + $json + '"') (Join-Path $Reports 'fe-test.log')
  if ($SkipBuild) { $res.build = 0 } else {
    Write-Host '[fe] npm run build' -ForegroundColor Cyan
    $res.build = Invoke-Logged $FE 'npm run build' (Join-Path $Reports 'fe-build.log')
  }
  return $res
}

# ------------------------------------------------------------------ đọc kết quả
function Read-BeResults {
  $dir = Join-Path $BE 'target\surefire-reports'
  $cases = @()
  if (Test-Path $dir) {
    foreach ($f in Get-ChildItem $dir -Filter 'TEST-*.xml') {
      $xml = New-Object System.Xml.XmlDocument
      $xml.Load($f.FullName)
      foreach ($tc in $xml.SelectNodes('//testcase')) {
        $status = 'pass'
        $msg = ''
        $bad = $tc.SelectSingleNode('failure')
        if (-not $bad) { $bad = $tc.SelectSingleNode('error') }
        if ($bad) { $status = 'fail'; $msg = [string]$bad.GetAttribute('message') }
        elseif ($tc.SelectSingleNode('skipped')) { $status = 'skip' }
        $cases += [pscustomobject]@{ cls = [string]$tc.classname; name = [string]$tc.name; status = $status; msg = $msg }
      }
    }
  }
  return , $cases
}

function Read-FeResults {
  $json = Join-Path $Reports 'vitest.json'
  $cases = @()
  if (Test-Path $json) {
    $data = Get-Content $json -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($tr in $data.testResults) {
      foreach ($a in $tr.assertionResults) {
        $cases += [pscustomobject]@{
          file = [string]$tr.name; name = [string]$a.fullName
          status = $(if ($a.status -eq 'passed') { 'pass' } elseif ($a.status -eq 'failed') { 'fail' } else { 'skip' })
          msg = $(if ($a.failureMessages) { ($a.failureMessages -join ' ') } else { '' })
        }
      }
    }
  }
  return , $cases
}

# ------------------------------------------------------------------ đánh giá check
function Short([string]$s) { if ($s.Length -gt 140) { return $s.Substring(0, 140) + '...' } else { return $s } }

function Eval-Check($check, $be, $fe) {
  switch ($check.kind) {
    'be' {
      $m = @($be | Where-Object { $_.name -eq $check.method -and $_.cls -like ('*' + $check.class) })
      if ($m.Count -eq 0) { return @{ ok = $false; text = "MISSING BE $($check.class)#$($check.method)" } }
      if ($m[0].status -eq 'pass') { return @{ ok = $true; text = "BE $($check.class)#$($check.method)" } }
      return @{ ok = $false; text = "FAIL BE $($check.class)#$($check.method): $(Short $m[0].msg)" }
    }
    'be-tag' {
      $m = @($be | Where-Object { $_.name -like ('*' + $check.tag + '*') })
      if ($m.Count -lt $check.min) { return @{ ok = $false; text = "MISSING BE tag $($check.tag) (có $($m.Count)/$($check.min))" } }
      $bad = @($m | Where-Object { $_.status -ne 'pass' })
      if ($bad.Count -gt 0) { return @{ ok = $false; text = "FAIL BE tag $($check.tag): $($bad[0].name) $(Short $bad[0].msg)" } }
      return @{ ok = $true; text = "BE tag $($check.tag) x$($m.Count)" }
    }
    'fe-tag' {
      $needle = '[' + $check.tag + ']'
      $m = @($fe | Where-Object { $_.name.Contains($needle) })
      if ($m.Count -lt $check.min) { return @{ ok = $false; text = "MISSING FE tag $needle (có $($m.Count)/$($check.min))" } }
      $bad = @($m | Where-Object { $_.status -ne 'pass' })
      if ($bad.Count -gt 0) { return @{ ok = $false; text = "FAIL FE tag ${needle}: $(Short $bad[0].name) $(Short $bad[0].msg)" } }
      return @{ ok = $true; text = "FE tag $needle x$($m.Count)" }
    }
    'grep' {
      $path = Join-Path $Root $check.file
      if (-not (Test-Path $path)) {
        if ($check.expect -eq 'absent') { return @{ ok = $true; text = "absent (không có file) $($check.file)" } }
        return @{ ok = $false; text = "MISSING file $($check.file)" }
      }
      $found = Select-String -Path $path -Pattern $check.pattern -Quiet
      if ($check.expect -eq 'present') {
        if ($found) { return @{ ok = $true; text = "grep present '$($check.pattern)' in $($check.file)" } }
        return @{ ok = $false; text = "FAIL grep: thiếu '$($check.pattern)' trong $($check.file)" }
      }
      if (-not $found) { return @{ ok = $true; text = "grep absent '$($check.pattern)' in $($check.file)" } }
      return @{ ok = $false; text = "FAIL grep: vẫn còn '$($check.pattern)' trong $($check.file)" }
    }
    'doc' {
      $path = Join-Path $Root $check.file
      if (-not (Test-Path $path)) { return @{ ok = $false; text = "MISSING doc $($check.file)" } }
      if (Select-String -Path $path -Pattern $check.pattern -Quiet) { return @{ ok = $true; text = "doc '$($check.pattern)' in $($check.file)" } }
      return @{ ok = $false; text = "FAIL doc: thiếu '$($check.pattern)' trong $($check.file)" }
    }
    default { return @{ ok = $false; text = "kind không hỗ trợ: $($check.kind)" } }
  }
}

# ------------------------------------------------------------------ một vòng
function Invoke-Iteration([int]$n) {
  $started = Get-Date
  $suite = @{ be = @{ ran = $false }; fe = @{ ran = $false } }
  if ($Stages -contains 'be') { $suite.be = Run-Be }
  if ($Stages -contains 'fe') { $suite.fe = Run-Fe }

  $be = Read-BeResults
  $fe = Read-FeResults
  $spec = Get-Content (Join-Path $Harness 'requirements.json') -Raw -Encoding UTF8 | ConvertFrom-Json

  $rows = @()
  foreach ($r in $spec.requirements) {
    $okAll = $true
    $evidence = @()
    foreach ($c in $r.checks) {
      $e = Eval-Check $c $be $fe
      if (-not $e.ok) { $okAll = $false }
      $evidence += $e.text
    }
    $rows += [pscustomobject]@{ id = $r.id; ref = $r.ref; title = $r.title; pass = $okAll; evidence = $evidence }
  }

  $beFail = @($be | Where-Object { $_.status -eq 'fail' })
  $feFail = @($fe | Where-Object { $_.status -eq 'fail' })
  $suiteOk = $true
  $suiteLines = @()
  if ($suite.be.ran) {
    $ok = ($suite.be.exit -eq 0)
    if (-not $ok) { $suiteOk = $false }
    $suiteLines += "- BE mvnw test: exit=$($suite.be.exit)" + $(if ($suite.be.partial) { ' (CHẠY MỘT PHẦN: -Dtest=' + $BeTests + ')' } else { '' }) + " · testcase=$($be.Count) · fail=$($beFail.Count)"
  } else { $suiteLines += "- BE: không chạy trong vòng này (dùng kết quả surefire còn lại, có thể cũ) · testcase=$($be.Count) · fail=$($beFail.Count)"; if ($beFail.Count -gt 0) { $suiteOk = $false } }
  if ($suite.fe.ran) {
    foreach ($k in 'lint', 'test', 'build') { if ($suite.fe[$k] -ne 0) { $suiteOk = $false } }
    $suiteLines += "- FE lint=$($suite.fe.lint) · vitest=$($suite.fe.test) · build=$($suite.fe.build) · testcase=$($fe.Count) · fail=$($feFail.Count)"
  } else { $suiteLines += "- FE: không chạy trong vòng này · testcase=$($fe.Count) · fail=$($feFail.Count)"; if ($feFail.Count -gt 0) { $suiteOk = $false } }

  $passN = @($rows | Where-Object { $_.pass }).Count
  $allGreen = ($passN -eq $rows.Count) -and $suiteOk -and ($Stages -contains 'be') -and ($Stages -contains 'fe') -and (-not $BeTests)

  # báo cáo markdown
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.AppendLine('# Harness Solution 2 — kết quả vòng ' + $n)
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine('Thời điểm: ' + $started.ToString('yyyy-MM-dd HH:mm:ss') + ' · tầng: ' + ($Stages -join ',') + ' · yêu cầu PASS: ' + $passN + '/' + $rows.Count)
  [void]$sb.AppendLine('')
  foreach ($l in $suiteLines) { [void]$sb.AppendLine($l) }
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine('| ID | Tham chiếu | Yêu cầu | Kết quả |')
  [void]$sb.AppendLine('|---|---|---|---|')
  foreach ($r in $rows) {
    $st = $(if ($r.pass) { 'PASS' } else { 'FAIL' })
    [void]$sb.AppendLine('| ' + $r.id + ' | ' + $r.ref + ' | ' + $r.title.Replace('|', '/') + ' | **' + $st + '** |')
  }
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine('## Chứng cứ theo yêu cầu')
  foreach ($r in $rows) {
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('### ' + $r.id + ' — ' + $(if ($r.pass) { 'PASS' } else { 'FAIL' }))
    foreach ($e in $r.evidence) { [void]$sb.AppendLine('- ' + $e) }
  }
  Write-Utf8 (Join-Path $Reports 'latest.md') $sb.ToString()
  Write-Utf8 (Join-Path $Reports 'latest.json') (($rows | Select-Object id, pass, evidence) | ConvertTo-Json -Depth 5)

  # việc cần làm tiếp
  $na = New-Object System.Text.StringBuilder
  [void]$na.AppendLine('# NEXT ACTIONS (vòng ' + $n + ')')
  [void]$na.AppendLine('')
  $failing = @($rows | Where-Object { -not $_.pass })
  if ($failing.Count -eq 0 -and $suiteOk) { [void]$na.AppendLine('Mọi yêu cầu PASS và bộ test xanh.') }
  foreach ($r in $failing) {
    [void]$na.AppendLine('## ' + $r.id + ' — ' + $r.title)
    foreach ($e in $r.evidence) { if ($e -match '^(MISSING|FAIL)') { [void]$na.AppendLine('- ' + $e) } }
    [void]$na.AppendLine('')
  }
  if ($suite.be.ran -and $suite.be.exit -ne 0) {
    [void]$na.AppendLine('## Tầng BE thất bại (exit=' + $suite.be.exit + ') — xem harness\reports\be.log')
    $errs = @(Select-String -Path $suite.be.log -Pattern 'ERROR|FAILURE|not recognized' -ErrorAction SilentlyContinue | Select-Object -First 25)
    foreach ($e in $errs) { [void]$na.AppendLine('- ' + (Short $e.Line.Trim())) }
    [void]$na.AppendLine('')
  }
  foreach ($k in 'lint', 'test', 'build') {
    if ($suite.fe.ran -and $suite.fe[$k] -ne 0) { [void]$na.AppendLine('## Tầng FE ' + $k + ' thất bại (exit=' + $suite.fe[$k] + ') — xem harness\reports\fe-' + $(if ($k -eq 'test') { 'test' } else { $k }) + '.log') }
  }
  if ($beFail.Count -gt 0) {
    [void]$na.AppendLine('## Test BE đang đỏ')
    foreach ($f in $beFail) { [void]$na.AppendLine('- ' + $f.cls + '#' + $f.name + ': ' + (Short $f.msg)) }
  }
  if ($feFail.Count -gt 0) {
    [void]$na.AppendLine('## Test FE đang đỏ')
    foreach ($f in $feFail) { [void]$na.AppendLine('- ' + $f.name + ': ' + (Short $f.msg)) }
  }
  Write-Utf8 (Join-Path $Reports 'NEXT_ACTIONS.md') $na.ToString()

  $line = ('{0} | iter={1} | stages={2}{3} | req {4}/{5} | beFail={6} feFail={7} | {8}' -f $started.ToString('yyyy-MM-dd HH:mm:ss'), $n, ($Stages -join ','),
    $(if ($BeTests) { ' be=' + $BeTests } else { '' }), $passN, $rows.Count, $beFail.Count, $feFail.Count, $(if ($allGreen) { 'ALL GREEN' } else { 'RED' }))
  Add-Content -Path (Join-Path $Reports 'history.log') -Value $line -Encoding UTF8

  Write-Host ''
  foreach ($l in $suiteLines) { Write-Host $l -ForegroundColor $(if ($suiteOk) { 'Gray' } else { 'Red' }) }
  Write-Host ("Yêu cầu PASS: $passN/$($rows.Count)  ·  BE fail=$($beFail.Count)  ·  FE fail=$($feFail.Count)") -ForegroundColor $(if ($allGreen) { 'Green' } else { 'Yellow' })
  foreach ($r in $failing) { Write-Host ("  ✗ " + $r.id + ' ' + $r.title) -ForegroundColor Red }
  if ($allGreen) { Write-Host 'ALL GREEN' -ForegroundColor Green }
  return @{ green = $allGreen; failing = $failing.Count }
}

# ------------------------------------------------------------------ vòng lặp
$result = $null
for ($i = 1; $i -le $MaxIterations; $i++) {
  Write-Host ("=== Vòng $i/$MaxIterations ===") -ForegroundColor White
  $result = Invoke-Iteration $i
  if ($result.green) { break }
  if ($i -lt $MaxIterations -and $FixCommand) {
    Write-Host "[fix] $FixCommand" -ForegroundColor Magenta
    $env:HARNESS_NEXT_ACTIONS = Join-Path $Reports 'NEXT_ACTIONS.md'
    cmd /c $FixCommand
  }
}
if ($result.green) { exit 0 } else { exit 1 }
