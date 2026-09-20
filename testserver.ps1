Add-Type -AssemblyName System.Net.HttpListener
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://127.0.0.1:8899/")
$listener.Start()
Write-Output "Test server on http://127.0.0.1:8899/ (Ctrl+C to stop)"

$testPage = @"
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body { font-family: sans-serif; padding: 20px; }
h3 { color: #6200EE; }
#result { white-space: pre-wrap; background: #f5f5f5; padding: 12px; border-radius: 8px; font-size: 12px; margin-top: 10px; min-height: 60px; }
.ok { color: green; font-weight: bold; }
.fail { color: red; font-weight: bold; }
button { padding: 12px 24px; margin: 6px; font-size: 14px; }
</style>
</head>
<body>
<h3>Shim Debug Page</h3>
<p>shimInstalled: <span id="shim">checking...</span></p>
<button onclick="testFetch()">fetch POST</button>
<button onclick="testXhr()">XHR POST</button>
<button onclick="testBridge()">Android.httpPost</button>
<div id="result">ready</div>
<script>
document.getElementById('shim').textContent =
  (window.__wsShimInstalled === true) ? 'YES (v1 style injected)' : 'NO';

function log(msg, cls) {
  var r = document.getElementById('result');
  r.innerHTML = '<div class="' + (cls||'') + '">' + msg + '</div>' + r.innerHTML;
}

function testFetch() {
  log('fetch POST to /echo ...');
  fetch('/echo', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'from=fetch&ts=' + Date.now()
  }).then(function(res) {
    return res.text().then(function(t) {
      log('fetch OK [' + res.status + ']: ' + t.substring(0, 200), 'ok');
    });
  }).catch(function(e) {
    log('fetch FAIL: ' + e.message, 'fail');
  });
}

function testXhr() {
  log('XHR POST to /echo ...');
  var x = new XMLHttpRequest();
  x.open('POST', '/echo');
  x.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
  x.onload = function() { log('XHR OK [' + x.status + ']: ' + (x.responseText||'').substring(0, 200), 'ok'); };
  x.onerror = function() { log('XHR FAIL: network error', 'fail'); };
  x.send('from=xhr&ts=' + Date.now());
}

function testBridge() {
  log('Android.httpPost to /echo ...');
  if (!window.Android || !window.Android.httpPost) { log('bridge FAIL: Android not available', 'fail'); return; }
  Android.httpPost('http://127.0.0.1:8899/echo', 'from=bridge&ts=' + Date.now(),
    'application/x-www-form-urlencoded', 'onBridgeDone');
}
function onBridgeDone(r) {
  if (r && r.ok) log('bridge OK [' + r.status + ']: ' + (r.data||'').substring(0, 200), 'ok');
  else log('bridge FAIL: ' + (r && r.error), 'fail');
}
</script>
</body>
</html>
"@

while ($listener.IsListening) {
  $ctx = $listener.GetContext()
  $req = $ctx.Request
  $resp = $ctx.Response
  $body = ""
  try {
    if ($req.HasEntityBody) {
      $reader = New-Object System.IO.StreamReader($req.InputStream, $req.ContentEncoding)
      $body = $reader.ReadToEnd()
    }
  } catch {}
  Write-Output ("[{0}] {1} {2} body={3}" -f (Get-Date -Format "HH:mm:ss"), $req.HttpMethod, $req.Url.AbsolutePath, $body)

  if ($req.Url.AbsolutePath -eq "/echo") {
    $json = '{"received":"' + $body + '","method":"' + $req.HttpMethod + '","ts":' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + '}'
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $resp.ContentType = "application/json"
    $resp.ContentLength64 = $bytes.Length
    $resp.OutputStream.Write($bytes, 0, $bytes.Length)
  } elseif ($req.Url.AbsolutePath -eq "/" -or $req.Url.AbsolutePath -eq "/index.html") {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($testPage)
    $resp.ContentType = "text/html; charset=utf-8"
    $resp.ContentLength64 = $bytes.Length
    $resp.OutputStream.Write($bytes, 0, $bytes.Length)
  } else {
    $resp.StatusCode = 404
    $bytes = [System.Text.Encoding]::UTF8.GetBytes("not found")
    $resp.OutputStream.Write($bytes, 0, $bytes.Length)
  }
  $resp.Close()
}
