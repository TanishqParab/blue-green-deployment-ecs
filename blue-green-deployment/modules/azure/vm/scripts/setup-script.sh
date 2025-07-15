#!/bin/bash

# Update system
sudo apt-get update -y
sudo apt-get install -y python3 python3-pip dos2unix git unzip curl wget

# Install Flask
pip3 install flask

# Install Azure CLI
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Install Java
sudo apt-get install -y openjdk-17-jdk

# Create Flask app based on app name
if [ "${app_name}" = "app_1" ]; then
cat > /home/${admin_username}/app.py << 'EOF'
from flask import Flask, jsonify
import os
import socket

app = Flask(__name__)

@app.route('/')
def home():
    return jsonify({
        'message': 'Hello from App 1 - Azure VM!',
        'app': 'app_1',
        'status': 'running',
        'hostname': socket.gethostname(),
        'platform': 'Azure VM'
    })

@app.route('/health')
def health():
    return jsonify({
        'status': 'healthy',
        'app': 'app_1',
        'platform': 'Azure VM'
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=80)
EOF

elif [ "${app_name}" = "app_2" ]; then
cat > /home/${admin_username}/app.py << 'EOF'
from flask import Flask, jsonify
import os
import socket

app = Flask(__name__)

@app.route('/')
def home():
    return jsonify({
        'message': 'Hello from App 2 - Azure VM!',
        'app': 'app_2',
        'status': 'running',
        'hostname': socket.gethostname(),
        'platform': 'Azure VM'
    })

@app.route('/health')
def health():
    return jsonify({
        'status': 'healthy',
        'app': 'app_2',
        'platform': 'Azure VM'
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=80)
EOF

elif [ "${app_name}" = "app_3" ]; then
cat > /home/${admin_username}/app.py << 'EOF'
from flask import Flask, jsonify
import os
import socket

app = Flask(__name__)

@app.route('/')
def home():
    return jsonify({
        'message': 'Hello from App 3 - Azure VM!',
        'app': 'app_3',
        'status': 'running',
        'hostname': socket.gethostname(),
        'platform': 'Azure VM'
    })

@app.route('/health')
def health():
    return jsonify({
        'status': 'healthy',
        'app': 'app_3',
        'platform': 'Azure VM'
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=80)
EOF
fi

# Set ownership
chown ${admin_username}:${admin_username} /home/${admin_username}/app.py

# Create systemd service
cat > /etc/systemd/system/flask-app-${app_name}.service << EOF
[Unit]
Description=Flask App for ${app_name}
After=network.target

[Service]
User=root
WorkingDirectory=/home/${admin_username}
ExecStart=/usr/bin/python3 /home/${admin_username}/app.py
Restart=always

[Install]
WantedBy=multi-user.target
EOF

# Enable and start service
systemctl daemon-reload
systemctl enable flask-app-${app_name}
systemctl start flask-app-${app_name}

echo "Setup completed for ${app_name}"