import urllib.request
import re
import json

url = 'https://data.mendeley.com/datasets/hx6f852hw4/2'
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
with urllib.request.urlopen(req) as resp:
    html = resp.read().decode('utf-8')

m = re.search(r'"configClient":({.*?}),"adobeAnalytics"', html)
if m:
    cfg = json.loads(m.group(1))
    print(json.dumps(cfg, indent=2))
else:
    # search for publicApiBaseUrl
    m2 = re.search(r'"publicApiBaseUrl":"([^"]+)"', html)
    if m2:
        print("publicApiBaseUrl:", m2.group(1))
    m3 = re.search(r'"apiBaseUrl":"([^"]+)"', html)
    if m3:
        print("apiBaseUrl:", m3.group(1))
