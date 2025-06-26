#!/usr/bin/env python3
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
        'deployment': os.environ.get('DEPLOYMENT_TYPE', 'blue'),
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

@app.route('/app3/')
def app_specific():
    return jsonify({
        'message': 'Welcome to App 3 on Azure!',
        'app': 'app_3',
        'path': '/app3/',
        'status': 'running',
        'deployment': os.environ.get('DEPLOYMENT_TYPE', 'blue'),
        'hostname': socket.gethostname(),
        'platform': 'Azure VM'
    })

@app.route('/app3/info')
def app_info():
    return jsonify({
        'app_name': 'Application 3',
        'version': '1.0.0',
        'description': 'Blue-Green Deployment App 3 on Azure VM',
        'endpoints': [
            '/',
            '/health',
            '/app3/',
            '/app3/info'
        ],
        'platform': 'Azure VM',
        'deployment': os.environ.get('DEPLOYMENT_TYPE', 'blue')
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=False)