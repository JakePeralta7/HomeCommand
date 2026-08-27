# Privacy Policy

**Last Updated:** August 27, 2026

## Overview

HomeCommand is a local-first MQTT client for Android that allows you to control and monitor smart home devices connected to your MQTT broker. This app is designed with privacy as a core principle: your data stays on your device and only communicates with your own MQTT broker.

## Data Collection

HomeCommand collects and processes the following data:

### Local Data
- **Rooms and Devices**: Configuration data for rooms and smart home devices you add (names, topics, device types, state history)
- **MQTT Settings**: Broker connection details (IP, port, username)
- **Device States**: Current state of all connected devices (temperature, power status, contact state, battery levels, etc.)
- **Logs**: Application event logs for debugging purposes
- **Notifications**: Settings for device state change notifications

### Remote Data
All communication occurs **only** with your own MQTT broker. HomeCommand never transmits data to external servers, and no telemetry or analytics are collected.

## Data Storage

All data is stored locally on your device:

- **Rooms and Devices**: Stored in encrypted Android SharedPreferences (`devices_v2` and `rooms` keys)
- **MQTT Broker Password**: Encrypted using AES-GCM via Android's AndroidKeyStore; passwords are not readable in plaintext
- **Device State History**: Kept in application memory with configurable retention (default: 100 values per topic)
- **Logs**: Stored locally in app-specific storage; accessible only to HomeCommand

## Security Measures

- **Password Encryption**: MQTT broker passwords are encrypted using AES-256-GCM with keys stored in Android's secure KeyStore
- **TLS/SSL Support**: Optional TLS encryption for connections to your MQTT broker
- **Local Control**: All data remains under your control on your device; no cloud sync or external backup

## Backup & Data

### Android Backup
- **Excluded**: MQTT settings (broker IP, username, encrypted password) are intentionally excluded from Android system backups because AndroidKeyStore encryption keys do not travel with backups
- **Included**: Rooms and device configurations are included in backups

### User Responsibility
You should maintain separate backups of your MQTT broker credentials outside this app, as they cannot be recovered if Android backup is your only record.

## User Control

You have full control over your data:

- **Delete Data**: You can delete rooms, devices, and all associated settings at any time
- **Edit Settings**: Modify any device configuration or MQTT settings
- **Export/Import**: Transfer configurations between devices or backups as needed (subject to manual export functionality if implemented)

## Third-Party Services

HomeCommand does **not** use any third-party analytics, telemetry, or data collection services. The only external communication is to your own MQTT broker, which you control.

## Changes to This Policy

We may update this privacy policy to reflect changes in the app or privacy practices. We encourage you to review this policy periodically.

## Contact

For privacy-related questions or concerns, please refer to the app's source code and documentation at https://github.com/anomalyco/opencode.
