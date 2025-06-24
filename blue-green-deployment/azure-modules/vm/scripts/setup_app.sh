#!/bin/bash

# Update system
sudo apt-get update -y

# Install Python and pip
sudo apt-get install -y python3 python3-pip

# Install Flask
pip3 install flask

# Create application directory
sudo mkdir -p /opt/app

# Create a simple Flask application
cat > /opt/app/app.py << EOF
from flask import Flask, jsonify
import os

app = Flask(__name__)

@app.route('/')
def home():
    return jsonify({
        'message': 'Hello from ${app_name}!',
        'app': '${app_name}',
        'status': 'running',
        'deployment': os.environ.get('DEPLOYMENT_TYPE', 'unknown')
    })

@app.route('/health')
def health():
    return jsonify({
        'status': 'healthy',
        'app': '${app_name}'
    })

@app.route('/${app_name}/')
def app_specific():
    return jsonify({
        'message': 'Welcome to ${app_name}!',
        'app': '${app_name}',
        'path': '/${app_name}/',
        'status': 'running'
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=${app_port}, debug=False)
EOF

# Create systemd service
sudo cat > /etc/systemd/system/${app_name}.service << EOF
[Unit]
Description=${app_name} Flask Application
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/app
Environment=DEPLOYMENT_TYPE=blue
ExecStart=/usr/bin/python3 /opt/app/app.py
Restart=always

[Install]
WantedBy=multi-user.target
EOF

# Enable and start the service
sudo systemctl daemon-reload
sudo systemctl enable ${app_name}.service
sudo systemctl start ${app_name}.service

# Install and configure nginx as reverse proxy
sudo apt-get install -y nginx

# Configure nginx
sudo cat > /etc/nginx/sites-available/${app_name} << EOF
server {
    listen 80;
    server_name _;

    location / {
        proxy_pass http://127.0.0.1:${app_port};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF

# Enable the site
sudo ln -sf /etc/nginx/sites-available/${app_name} /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo systemctl restart nginx
sudo systemctl enable nginx

echo "Setup completed for ${app_name}"