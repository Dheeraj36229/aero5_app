import json
import urllib.request

url = "https://aero5-886bf-default-rtdb.asia-southeast1.firebasedatabase.app/app_update.json"
data = {
    "latest_version": "5.0.8",
    "apk_url": "https://raw.githubusercontent.com/Dheeraj36229/aero5_app/main/AERO5_v5.apk",
    "changelog": "Performance improvements and automated update sync."
}

req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), method='PUT')
req.add_header('Content-Type', 'application/json')

try:
    with urllib.request.urlopen(req) as f:
        print(f.read().decode('utf-8'))
    print("Successfully updated Firebase to 5.0.8")
except Exception as e:
    print(f"Error updating Firebase: {e}")
