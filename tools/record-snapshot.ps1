# Records the analyzer test fixtures (read-only GETs):
#   src/test/resources/bazaar-snapshot.json   Hypixel Bazaar response, order books cut to the top 2 entries
#   src/test/resources/coflnet-history.json   Coflnet price history for every product that passes the
#                                             pre-history filters (same rules as flipcompare.ps1's AnalyzerReject)
$ErrorActionPreference = 'Stop'
$res = Join-Path $PSScriptRoot '..\src\test\resources'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$bz = Invoke-RestMethod 'https://api.hypixel.net/v2/skyblock/bazaar'
foreach ($p in $bz.products.PSObject.Properties) {
    $v = $p.Value
    $v.sell_summary = @($v.sell_summary | Select-Object -First 2)
    $v.buy_summary  = @($v.buy_summary  | Select-Object -First 2)
}
[IO.File]::WriteAllText((Join-Path $res 'bazaar-snapshot.json'), ($bz | ConvertTo-Json -Depth 10 -Compress), $utf8)

$tf = 1 - 1.25 / 100
function PreHistoryPass($v) {
    $q = $v.quick_status
    $bid  = if ($v.sell_summary.Count -gt 0) { [double]$v.sell_summary[0].pricePerUnit } else { 0 }
    $bid2 = if ($v.sell_summary.Count -gt 1) { [double]$v.sell_summary[1].pricePerUnit } else { 0 }
    $ask  = if ($v.buy_summary.Count  -gt 0) { [double]$v.buy_summary[0].pricePerUnit }  else { 0 }
    $buy = [math]::Round($bid + 0.1, 1); $sell = [math]::Round($ask - 0.1, 1)
    if ($sell -le $buy) { return $false }
    $profit = [math]::Round($sell * $tf - $buy, 1); if ($profit -le 0) { return $false }
    $m = $profit / $buy * 100; if ($m -lt 0.5 -or $m -gt 25) { return $false }
    if ($buy -lt 600000) { return $false }
    if ([math]::Min([double]$q.sellMovingWeek, [double]$q.buyMovingWeek) -lt 168) { return $false }
    if ($bid2 -gt 0 -and ($bid - $bid2) -gt [math]::Max($bid * 0.05, 500)) { return $false }
    if ($q.sellPrice -gt 0 -and [math]::Abs($buy - $q.sellPrice) / $q.sellPrice * 100 -gt 30) { return $false }
    if ($q.buyPrice -gt 0 -and [math]::Abs($sell - $q.buyPrice) / $q.buyPrice * 100 -gt 30) { return $false }
    return $true
}

$history = [ordered]@{}
$ids = @($bz.products.PSObject.Properties | Where-Object { PreHistoryPass $_.Value } | ForEach-Object Name | Sort-Object)
"Bazaar products: $(@($bz.products.PSObject.Properties).Count), passing pre-history filters: $($ids.Count)"
foreach ($id in $ids) {
    try {
        $h = Invoke-RestMethod ("https://sky.coflnet.com/api/item/price/" + $id) -TimeoutSec 10
        $history[$id] = [ordered]@{ min = $h.min; median = $h.median; mean = $h.mean; mode = $h.mode; volume = $h.volume; max = $h.max }
    } catch { "  history failed for ${id}: $($_.Exception.Message)" }
    Start-Sleep -Milliseconds 300
}
[IO.File]::WriteAllText((Join-Path $res 'coflnet-history.json'), ($history | ConvertTo-Json -Depth 5), $utf8)
"Recorded history for $($history.Count) products. lastUpdated $([DateTimeOffset]::FromUnixTimeMilliseconds($bz.lastUpdated).UtcDateTime) UTC"
