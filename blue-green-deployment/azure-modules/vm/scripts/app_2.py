#!/usr/bin/env python3
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
        'deployment': os.environ.get('DEPLOYMENT_TYPE', 'blue'),
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

@app.route('/app2/')
def app_specific():
    return jsonify({
        'message': 'Welcome to App 2 on Azure!',
        'app': 'app_2',
        'path': '/app2/',
        'status': 'running',
        'deployment': os.environ.get('DEPLOYMENT_TYPE', 'blue'),
        'hostname': socket.gethostname(),
        'platform': 'Azure VM'
    })

@app.route('/app2/info')
def app_info():
    return jsonify({
        'app_name': 'Application 2',
        'version': '1.0.0',
        'description': 'Blue-Green Deployment App 2 on Azure VM',
        'endpoints': [
            '/',
            '/health',
            '/app2/',
            '/app2/info'
        ],
        'platform': 'Azure VM',
        'deployment': os.environ.get('DEPLOYMENT_TYPE', 'blue')
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=False)