param(
    [Parameter(Mandatory)]
    [string] $MySqlHome,

    [string] $MavenSettings
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$mysqlServer = (Resolve-Path -LiteralPath (Join-Path $MySqlHome 'bin\mysqld.exe')).Path
$mysqlClient = (Resolve-Path -LiteralPath (Join-Path $MySqlHome 'bin\mysql.exe')).Path
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java.exe).Source }
$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$workDir = Join-Path $tempRoot ("studysphere-native-" + [guid]::NewGuid().ToString('N'))
$server = $null
$backend = $null
$rootPassword = $null
$envNames = 'MYSQL_PWD', 'MYSQL_TEST_LOGIN_FILE', 'STUDYSPHERE_TEST_DB_URL', 'STUDYSPHERE_TEST_DB_USER',
    'STUDYSPHERE_TEST_DB_PASSWORD', 'STUDYSPHERE_DB_PORT', 'STUDYSPHERE_DB_NAME',
    'STUDYSPHERE_DB_USER', 'STUDYSPHERE_DB_PASSWORD'
$previousEnvironment = @{}
foreach ($name in $envNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}

function Get-LoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return $listener.LocalEndpoint.Port
    } finally {
        $listener.Stop()
    }
}

function Stop-TestProcess($process) {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id
        if (-not $process.WaitForExit(10000)) {
            throw "Process $($process.Id) did not stop within 10 seconds."
        }
    }
}

try {
    New-Item -ItemType Directory -Path $workDir | Out-Null
    $dataDir = Join-Path $workDir 'data'
    $initLog = Join-Path $workDir 'initialize.log'
    $mysqlLog = Join-Path $workDir 'mysqld.log'
    $mysqlPort = Get-LoopbackPort
    $clientArgs = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', "--port=$mysqlPort", '--user=root', '--connect-timeout=3')

    & $mysqlServer --no-defaults --initialize "--basedir=$MySqlHome" "--datadir=$dataDir" "--log-error=$initLog"
    if ($LASTEXITCODE -ne 0) { throw "MySQL initialization failed ($LASTEXITCODE)." }

    $temporaryPassword = [regex]::Match(
        (Get-Content -LiteralPath $initLog -Raw),
        'temporary password is generated for root@localhost: (\S+)'
    ).Groups[1].Value
    if (-not $temporaryPassword) { throw 'No temporary MySQL root credential was generated.' }

    $serverArgs = '--no-defaults --no-monitor --basedir="{0}" --datadir="{1}" --bind-address=127.0.0.1 --port={2} --mysqlx=OFF --log-error="{3}"' -f $MySqlHome, $dataDir, $mysqlPort, $mysqlLog
    $server = Start-Process -FilePath $mysqlServer -ArgumentList $serverArgs -WindowStyle Hidden -PassThru
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($server.HasExited) { throw "MySQL exited ($($server.ExitCode))." }
        if ((Test-Path -LiteralPath $mysqlLog) -and
            (Select-String -LiteralPath $mysqlLog -SimpleMatch 'ready for connections' -Quiet)) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'MySQL did not start within 60 seconds.' }

    $rootPassword = 'Aa1!' + [guid]::NewGuid().ToString('N')
    $testPassword = 'Aa1!' + [guid]::NewGuid().ToString('N')
    $env:MYSQL_PWD = $temporaryPassword
    $env:MYSQL_TEST_LOGIN_FILE = Join-Path $workDir 'unused-login.cnf'
    $setupSql = @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';
CREATE DATABASE studysphere_verify CHARACTER SET utf8mb4;
CREATE DATABASE studysphere_smoke CHARACTER SET utf8mb4;
CREATE USER 'studysphere_check'@'127.0.0.1' IDENTIFIED BY '$testPassword';
GRANT ALL PRIVILEGES ON studysphere_verify.* TO 'studysphere_check'@'127.0.0.1';
GRANT ALL PRIVILEGES ON studysphere_smoke.* TO 'studysphere_check'@'127.0.0.1';
"@
    $setupSql | & $mysqlClient @clientArgs --connect-expired-password
    if ($LASTEXITCODE -ne 0) { throw "MySQL schema provisioning failed ($LASTEXITCODE)." }
    Remove-Item Env:MYSQL_PWD
    'Fresh native MySQL schemas ready.'

    $env:STUDYSPHERE_TEST_DB_URL = "jdbc:mysql://127.0.0.1:$mysqlPort/studysphere_verify"
    $env:STUDYSPHERE_TEST_DB_USER = 'studysphere_check'
    $env:STUDYSPHERE_TEST_DB_PASSWORD = $testPassword

    $mavenArgs = @('-B', '-ntp', '-f', (Join-Path $repoRoot 'backend\pom.xml'), 'clean', 'verify')
    if ($MavenSettings) { $mavenArgs = @('-s', (Resolve-Path -LiteralPath $MavenSettings).Path) + $mavenArgs }
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven verify failed ($LASTEXITCODE)." }

    $httpPort = Get-LoopbackPort
    $env:STUDYSPHERE_DB_PORT = [string]$mysqlPort
    $env:STUDYSPHERE_DB_NAME = 'studysphere_smoke'
    $env:STUDYSPHERE_DB_USER = 'studysphere_check'
    $env:STUDYSPHERE_DB_PASSWORD = $testPassword
    $jar = (Resolve-Path -LiteralPath (Join-Path $repoRoot 'backend\target\studysphere-backend-0.1.0-SNAPSHOT.jar')).Path
    $backendArgs = '-jar "{0}" --server.address=127.0.0.1 --server.port={1}' -f $jar, $httpPort
    $backendOut = Join-Path $workDir 'backend.out.log'
    $backend = Start-Process -FilePath $java -ArgumentList $backendArgs -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $backendOut -RedirectStandardError (Join-Path $workDir 'backend.err.log') -PassThru
    $health = $null
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($backend.HasExited) {
            Get-Content -LiteralPath $backendOut -Tail 25
            throw "Packaged backend exited ($($backend.ExitCode))."
        }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$httpPort/actuator/health" -TimeoutSec 3
            break
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if ($health.status -ne 'UP') { throw 'Packaged backend failed the health check.' }
    'Packaged backend boot passed. GET /actuator/health returned UP.'
} finally {
    try {
        Stop-TestProcess $backend
    } catch {
        Write-Warning "Could not stop the test backend. $($_.Exception.Message)" -WarningAction Continue
    }
    try {
        if ($server -and -not $server.HasExited) {
            if ($rootPassword) {
                try {
                    $env:MYSQL_PWD = $rootPassword
                    'SHUTDOWN;' | & $mysqlClient @clientArgs
                    if ($LASTEXITCODE -ne 0) { throw "MySQL client exited with code $LASTEXITCODE." }
                } catch {
                    Write-Warning "Graceful MySQL shutdown failed. $($_.Exception.Message)" -WarningAction Continue
                }
            }
            if (-not $server.WaitForExit(5000)) { Stop-TestProcess $server }
        }
    } catch {
        Write-Warning "Could not stop the test MySQL server. $($_.Exception.Message)" -WarningAction Continue
    }
    foreach ($name in $envNames) {
        if ($null -eq $previousEnvironment[$name]) {
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name])
        }
    }
    try {
        if (Test-Path -LiteralPath $workDir -PathType Container) {
            $resolved = (Resolve-Path -LiteralPath $workDir).Path
            if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
                (Split-Path -Leaf $resolved) -match '^studysphere-native-[a-f0-9]{32}$') {
                Remove-Item -LiteralPath $resolved -Recurse -Force
            }
        }
    } catch {
        Write-Warning "Could not remove the temporary test directory '$workDir'. $($_.Exception.Message)" -WarningAction Continue
    }
}
