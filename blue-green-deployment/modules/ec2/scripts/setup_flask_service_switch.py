import os
import sys
import time
import socket
import urllib.request
import glob

# Fetch public IP using IMDSv2
def get_instance_public_ip():
    try:
        token_req = urllib.request.Request(
            "http://169.254.169.254/latest/api/token",
            method="PUT",
            headers={"X-aws-ec2-metadata-token-ttl-seconds": "21600"}
        )
        token = urllib.request.urlopen(token_req).read().decode()

        metadata_req = urllib.request.Request(
            "http://169.254.169.254/latest/meta-data/public-ipv4",
            headers={"X-aws-ec2-metadata-token": token}
        )
        public_ip = urllib.request.urlopen(metadata_req).read().decode()
        return public_ip
    except Exception as e:
        print(f"⚠️ Failed to retrieve public IP: {e}")
        return None

# Input arguments
app_name = sys.argv[1] if len(sys.argv) > 1 else "default"
mode = sys.argv[2] if len(sys.argv) > 2 else "switch"

app_suffix = app_name.replace("app", "app_")
service_name = f"flask-app-{app_suffix}"
service_file = f"/etc/systemd/system/{service_name}.service"
app_script = ""

app_number = app_name[3:] if app_name.startswith("app") else app_name

# App version resolution
if mode == "rollback":
    print("🛑 Rollback mode triggered")
    version_files = sorted(
        glob.glob(f"/home/ec2-user/app_{app_number}_v*.py"),
        key=os.path.getmtime,
        reverse=True
    )
    if len(version_files) >= 2:
        app_script = version_files[1]  # Second newest = previous version
        print(f"🔙 Rolling back to previous version: {app_script}")
    elif version_files:
        app_script = version_files[0]
        print(f"⚠️ Only one version found. Using: {app_script}")
    else:
        # Try fallback to Terraform-provisioned initial version
        initial_file = f"/home/ec2-user/app_app_{app_number}.py"
        if os.path.exists(initial_file):
            app_script = initial_file
            print(f"🕹️ No versioned files found. Falling back to initial version: {app_script}")
        else:
            print("❌ No rollback targets found: No versioned or initial files available.")
            sys.exit(1)
else:
    print("🚀 Switch mode triggered")
    version_files = sorted(
        glob.glob(f"/home/ec2-user/app_{app_number}_v*.py"),
        key=os.path.getmtime,
        reverse=True
    )
    if version_files:
        app_script = version_files[0]
        print(f"✅ Latest app version detected: {app_script}")
    else:
        # Fallback to default
        app_script = f"/home/ec2-user/app_{app_number}.py"
        print(f"⚠️ No versioned files found. Using fallback: {app_script}")

print(f"App name: {app_name}")
print(f"Mode: {mode}")
print(f"Using app script: {app_script}")

# Stop existing service
print(f"🔻 Stopping existing service: {service_name}")
os.system(f"sudo systemctl stop {service_name} 2>/dev/null || true")
os.system(f"sudo systemctl disable {service_name} 2>/dev/null || true")
os.system("sudo fuser -k 80/tcp 2>/dev/null || true")

# Create systemd service file
service_content = f"""[Unit]
Description=Flask App for {app_name} ({mode.capitalize()} Mode)
After=network.target

[Service]
User=root
WorkingDirectory=/home/ec2-user
ExecStart=/usr/bin/python3 {app_script}
Restart=always

[Install]
WantedBy=multi-user.target
"""

try:
    with open(service_file, "w") as f:
        f.write(service_content)
    print(f"✅ Created/Updated systemd service: {service_file}")
except PermissionError:
    print("❌ Permission denied: run with sudo")
    sys.exit(1)

# Start updated service
os.system("sudo systemctl daemon-reload")
os.system(f"sudo systemctl enable {service_name}")
os.system(f"sudo systemctl start {service_name}")

# Health check
print("⏳ Waiting for app to start on port 80...")
time.sleep(5)

try:
    with urllib.request.urlopen("http://127.0.0.1", timeout=3) as response:
        if response.status == 200:
            print("✅ Flask app responded successfully on localhost.")
        else:
            print(f"⚠️ App responded with status: {response.status}")
except Exception as e:
    print(f"❌ Health check failed on localhost: {e}")

# Public IP info
public_ip = get_instance_public_ip()
if public_ip:
    print(f"🌐 App should be accessible at: http://{public_ip}")
else:
    print("❌ Could not retrieve public IP.")
