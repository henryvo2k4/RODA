# RODA

<p align="center">
  <img src="images/logo_main.png" width="160">
</p>

<h2 align="center">Road Monitoring & Incident Warning System</h2>

<p align="center">
  <strong>IoT · Embedded Systems · GIS · AI · Cloud</strong>
</p>

<p align="center">
  An end-to-end IoT-GIS platform for monitoring road conditions,
  detecting urban traffic incidents, and visualizing road data in real time.
</p>

<p align="center">
  <a href="#overview">Overview</a> •
  <a href="#system-architecture">Architecture</a> •
  <a href="#embedded-system">Embedded</a> •
  <a href="#ai--gis">AI & GIS</a> •
  <a href="#applications">Applications</a> •
  <a href="#results">Results</a>
</p>

---

## Overview

**RODA** is an IoT-GIS road monitoring system developed as an individual
graduation project.

The system combines embedded IoT devices, cloud services, AI-based image
verification, GIS technologies, Web applications, and an Android application
to monitor and manage urban road incidents.

### Supported Incidents

| Incident | Detection Method |
|---|---|
| 🕳️ Pothole | ESP32 + MPU6050 |
| 🌊 Flooding | ESP32 + JSN-SR04T |
| 🚧 Construction | AI image verification |
| 📍 Road incidents | GPS / GIS |

---

## System Architecture

<p align="center">
  <img src="images/system-architecture.png" width="900">
</p>

RODA consists of four major layers:

```text
┌───────────────────────┐
│    EMBEDDED LAYER     │
│                       │
│  Mobile Node          │
│  Fixed Node           │
└───────────┬───────────┘
            │
            │ HTTPS / REST / JSON
            ▼
┌───────────────────────┐
│     CLOUD LAYER       │
│                       │
│  Supabase             │
│  PostgreSQL           │
│  Storage              │
└───────────┬───────────┘
            │
      ┌─────┴─────┐
      ▼           ▼
┌───────────┐ ┌────────────────┐
│ AI / GIS  │ │ APPLICATIONS   │
│           │ │                │
│ YOLOv8    │ │ Android        │
│ Leaflet   │ │ Web Dashboard  │
└───────────┘ └────────────────┘
```

---

# Embedded System

RODA uses two ESP32-based IoT nodes designed for different monitoring
scenarios.

## Mobile Node

<p align="center">
  <img src="images/mobile-node.jpg" width="700">
</p>

The Mobile Node is designed to be installed on a moving vehicle for
road-surface monitoring.

### Hardware

- ESP32
- MPU6050
- NEO-6M GPS
- MicroSD
- 18650 Li-ion Battery
- TP4056

### Functions

- Detect potholes from vehicle acceleration.
- Acquire GPS coordinates.
- Store incident data locally.
- Synchronize stored data with the cloud.
- Continue monitoring without an active Internet connection.

### Pothole Detection

The MPU6050 continuously measures acceleration.

The system processes the Z-axis acceleration and triggers a pothole event
when the configured threshold is exceeded.

```text
MPU6050
   │
   ▼
Acceleration Sampling
   │
   ▼
Z-axis Processing
   │
   ▼
Threshold Detection
   │
   ├── Normal
   │
   └── Pothole Detected
            │
            ▼
         Read GPS
            │
            ▼
      Store to MicroSD
```

---

## Store-and-Forward

A key feature of the Mobile Node is the **Store-and-Forward architecture**.

The system separates road monitoring from cloud synchronization.

### Sensing

```text
Vehicle Moving
      │
      ▼
Read MPU6050
      │
      ▼
Detect Pothole
      │
      ▼
Read GPS
      │
      ▼
Store Lat / Lng / Time
to MicroSD
```

### Synchronization

```text
Vehicle Stops
      │
      ▼
Start Synchronization
      │
      ▼
Enable Wi-Fi
      │
      ▼
Read Local Data
      │
      ▼
Create JSON Payload
      │
      ▼
HTTP POST
      │
      ▼
Supabase
```

This allows the device to continue collecting incident data during network
outages and synchronize the stored records when connectivity is restored.

---

# Fixed Node

<p align="center">
  <img src="images/fixed-node.jpg" width="700">
</p>

The Fixed Node is designed for outdoor deployment at flood-prone locations.

### Hardware

- ESP32
- JSN-SR04T waterproof ultrasonic sensor
- Solar panel
- 18650 Li-ion battery
- Power management circuitry

### Functions

- Measure water level.
- Calculate flooding depth.
- Detect configurable flood thresholds.
- Send incident alerts to the cloud.
- Operate periodically using Deep Sleep.

### Flood Detection

```text
JSN-SR04T
    │
    ▼
Measure Distance
    │
    ▼
Compare with Reference Distance
    │
    ▼
Calculate Water Level
    │
    ├── Normal
    │
    └── Flood Detected
             │
             ▼
       Create JSON Payload
             │
             ▼
        HTTPS / REST API
             │
             ▼
          Supabase
```

---

# Low-Power Design

The Fixed Node uses periodic wake-up and **ESP32 Deep Sleep** to reduce
energy consumption.

```text
       ┌──────────────┐
       │  Deep Sleep  │
       └──────┬───────┘
              │
              ▼
         Wake Up Timer
              │
              ▼
         Read Sensor
              │
              ▼
       Process Water Level
              │
              ▼
        Send Data via Wi-Fi
              │
              ▼
         Return to Sleep
```

### Measured Results

| State | Current |
|---|---:|
| Active / Wi-Fi | ~120 mA |
| Deep Sleep | ~15 µA |
| Average | ~1.35 mA |

---

# Cloud & Data Pipeline

RODA uses **Supabase** as the cloud backend.

### Components

- PostgreSQL
- REST API
- Supabase Storage
- HTTPS
- JSON

### Data Flow

```text
ESP32
  │
  │ JSON
  ▼
HTTPS REST API
  │
  ▼
Supabase
  │
  ├── PostgreSQL
  ├── Storage
  └── API
       │
       ├── Android
       ├── Web
       └── AI Service
```

Each IoT node is identified using a unique `device_id`.

Example payload:

```json
{
  "device_id": "NODE_CODINH_01",
  "lat": 10.732,
  "lng": 106.721,
  "type": "Flood",
  "status": "approved",
  "description": "Flood detected",
  "created_at": "2026-05-18T14:30:00+07:00"
}
```

---

# AI & GIS

## AI Incident Verification

RODA integrates an AI pipeline for image-based incident verification.

### Stack

- Python
- YOLOv8
- Hugging Face

### Detection Classes

- Pothole
- Flood
- Construction

### Pipeline

```text
Incident Image
      │
      ▼
AI Service
      │
      ▼
YOLOv8
      │
      ▼
Object Detection
      │
      ▼
Confidence Evaluation
      │
      ▼
Incident Verification
      │
      ▼
Cloud Status Update
```

---

## GIS

The GIS layer provides geographic visualization and spatial processing of
road incidents.

### Technologies

- OpenStreetMap
- Leaflet.js
- Turf.js
- OSRM
- Overpass API
- OSMDroid

### Features

- Interactive incident map
- GPS-based incident markers
- Road visualization
- Route calculation
- Road snapping
- Spatial processing
- Incident filtering

---

# Applications

## Android Application

<p align="center">
  <img src="images/android-app.png" width="380">
</p>

The Android application provides mobile access to the RODA platform.

### Features

- Report road incidents
- Capture incident images
- Acquire location
- View incidents on GIS maps
- Access cloud data
- Submit images for AI verification
- Routing and map interaction

### Technology

**Java · Android SDK · OSMDroid · OkHttp**

---

## Web Dashboard

<p align="center">
  <img src="images/web-dashboard.png" width="900">
</p>

The Web Dashboard provides centralized monitoring and management of road
incident data.

### Features

- GIS incident visualization
- Incident management
- Device monitoring
- Data filtering
- Road-event visualization
- Administrative functions

### Technology

**HTML · CSS · JavaScript · Leaflet.js · OpenStreetMap · REST API**

---

# Communication

RODA integrates multiple communication interfaces across the embedded and
cloud layers.

| Interface | Application |
|---|---|
| **I²C** | ESP32 ↔ MPU6050 |
| **UART** | ESP32 ↔ GPS |
| **SPI** | ESP32 ↔ MicroSD |
| **Wi-Fi** | ESP32 ↔ Internet |
| **HTTPS** | IoT ↔ Cloud |
| **REST API** | Cloud ↔ Applications |
| **JSON** | Data Exchange |

---

# Results

## Mobile Node

The Store-and-Forward mechanism was tested under different operating
conditions.

| Test | Result |
|---|---:|
| 30 km/h | 10/10 synchronized |
| 50 km/h | 7/7 synchronized |
| Network unavailable | 15/15 synchronized |
| Observed data loss | **0%** |

## Fixed Node

JSN-SR04T measurements produced errors of approximately:

**0.1 – 0.3 cm**

| Scenario | Actual | Measured | Error |
|---|---:|---:|---:|
| Dry road | 100 cm | 99.8 cm | 0.2 cm |
| Water rising | 98 cm | 98.1 cm | 0.1 cm |
| Light flooding | 94 cm | 94.3 cm | 0.3 cm |
| Deep flooding | 79 cm | 79.2 cm | 0.2 cm |

---

# Technology Stack

### Embedded

`C/C++` `ESP32` `MPU6050` `JSN-SR04T` `NEO-6M` `MicroSD`
`I²C` `UART` `SPI` `Wi-Fi` `Deep Sleep`

### Cloud

`Supabase` `PostgreSQL` `REST API` `HTTPS` `JSON`
`Supabase Storage`

### AI

`Python` `YOLOv8` `Hugging Face`

### GIS

`OpenStreetMap` `Leaflet.js` `Turf.js` `OSRM`
`Overpass API` `OSMDroid`

### Applications

`Java` `Android SDK` `HTML` `CSS` `JavaScript` `Vercel`

---

# Repository Structure

```text
RODA/
│
├── hardware/
│   ├── NodeDiDong/
│   ├── NodeCoDinh/
│   └── schematics/
│
├── android/
│
├── web/
│
├── ai/
│
├── images/
│   ├── logo_main.png
│   ├── system-architecture.png
│   ├── mobile-node.jpg
│   ├── fixed-node.jpg
│   ├── web-dashboard.png
│   └── android-app.png
│
└── README.md
```

---

# Project Status

| Component | Status |
|---|---|
| Mobile IoT Node | ✅ Completed |
| Fixed IoT Node | ✅ Completed |
| Store-and-Forward | ✅ Completed |
| Cloud Backend | ✅ Completed |
| AI Verification | ✅ Completed |
| GIS Platform | ✅ Completed |
| Android Application | ✅ Completed |
| Web Dashboard | ✅ Completed |

---

# Author

<p align="center">
  <strong>Hen</strong><br>
  Embedded Systems & IoT Developer
</p>

---

> Developed as an individual graduation project.
