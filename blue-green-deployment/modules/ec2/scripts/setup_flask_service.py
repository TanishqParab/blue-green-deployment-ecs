import os
import sys
import subprocess

app_name = sys.argv[1] if len(sys.argv) > 1 else "default"

service_name = f"flask-app-{app_name}"
service_file = f"/etc/systemd/system/{service_name}.service"
app_script = f"/home/ec2-user/app_{app_name}.py"

# Stop and disable existing service if running
print(f"Stopping and disabling existing service {service_name} if any...")
subprocess.run(["sudo", "systemctl", "stop", service_name], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
subprocess.run(["sudo", "systemctl", "disable", service_name], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

# Create systemd service file content
service_content = f"""[Unit]
Description=Flask App for {app_name}
After=network.target

[Service]
User=root
WorkingDirectory=/home/ec2-user
ExecStart=/usr/bin/python3 {app_script}
Restart=always

[Install]
WantedBy=multi-user.target
"""

# Write service file
with open(service_file, "w") as f:
    f.write(service_content)

# Reload systemd daemon to pick up new service file
print("Reloading systemd daemon...")
subprocess.run(["sudo", "systemctl", "daemon-reload"])

# Enable and start the new service
print(f"Enabling and starting service {service_name}...")
subprocess.run(["sudo", "systemctl", "enable", service_name])
subprocess.run(["sudo", "systemctl", "start", service_name])

print(f"Flask service for {app_name} has been set up and started.")
