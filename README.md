# RODA - Road Monitoring & Incident Warning System

<p align="center">
  <img src="images/logo_main.png" width="180">
</p>

<h3 align="center">
  IoT-GIS System for Multi-dimensional Road Surface Condition Monitoring
  and Urban Traffic Incident Warning
</h3>

<p align="center">
  An end-to-end IoT platform combining Embedded Systems, GIS, Cloud Computing,
  AI, Web and Android for intelligent road monitoring.
</p>

<p align="center">
  <img src="images/roda-overview.png" width="850">
</p>

---

## 📌 Overview

**RODA** is an end-to-end IoT-GIS road monitoring system designed to collect,
process, verify and visualize urban road incidents such as:

- 🕳️ Potholes
- 🌊 Flooding
- 🚧 Road construction / obstacles
- 📍 Road incidents with geographic coordinates

The system combines two types of ESP32-based IoT nodes with a cloud backend,
AI verification pipeline, GIS visualization, Web Dashboard and Android
application.

RODA was developed as an **individual graduation project**.

---

## 🎯 Objectives

The main objectives of RODA are:

- Detect road surface abnormalities using embedded sensors.
- Detect flooding at fixed monitoring locations.
- Collect geographic coordinates for detected incidents.
- Continue collecting data when Internet connectivity is unavailable.
- Synchronize locally stored data when network connectivity is restored.
- Upload and manage incident data through a cloud backend.
- Use AI to verify incident images.
- Visualize incidents on an interactive GIS map.
- Provide Web and Android interfaces for monitoring and reporting.
- Design an energy-efficient fixed monitoring node for outdoor deployment.

---

# 🏗️ System Architecture

RODA is organized into four main layers:

```text
┌──────────────────────────────────────────────────────────────┐
│                         RODA SYSTEM                          │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  1. EMBEDDED / IoT LAYER                                    │
│                                                              │
│     ┌─────────────────┐       ┌─────────────────┐            │
│     │   Mobile Node   │       │   Fixed Node    │            │
│     │     ESP32       │       │     ESP32       │            │
│     │                 │       │                 │            │
│     │ MPU6050         │       │ JSN-SR04T       │            │
│     │ GPS NEO-6M      │       │ Solar + 18650   │            │
│     │ MicroSD         │       │ Wi-Fi           │            │
│     └────────┬────────┘       └────────┬────────┘            │
│              │                         │                     │
│              └────────────┬────────────┘                     │
│                           │                                  │
│                           ▼                                  │
│  2. CLOUD / DATA LAYER                                      │
│                                                              │
│                  ┌─────────────────────┐                     │
│                  │      Supabase       │                     │
│                  │                     │                     │
│                  │ PostgreSQL          │                     │
│                  │ REST API            │                     │
│                  │ Storage             │                     │
│                  └──────────┬──────────┘                     │
│                             │                                │
│                ┌────────────┴────────────┐                   │
│                ▼                         ▼                   │
│  3. AI / GIS LAYER                4. APPLICATION LAYER       │
│                                                              │
│  ┌────────────────────┐       ┌────────────────────┐         │
│  │ YOLOv8             │       │ Web Dashboard      │         │
│  │ Python             │       │ Android App        │         │
│  │ Hugging Face       │       │ GIS Visualization  │         │
│  └────────────────────┘       └────────────────────┘         │
│                                                              │
└──────────────────────────────────────────────────────────────┘
🔧 Embedded System

RODA uses two different IoT node architectures for different deployment
scenarios.

🚗 Mobile Node

The Mobile Node is designed to be installed on a moving vehicle for
road-surface monitoring.

Hardware
ESP32
MPU6050
GY-GPS6MV2 / NEO-6M GPS
MicroSD / TF Card
18650 Li-ion Battery
TP4056 charging module
Main Functions
Detect pothole events from vehicle acceleration.
Obtain GPS coordinates of detected incidents.
Store incident data locally when Wi-Fi is unavailable.
Synchronize stored data to the cloud when connectivity is restored.
Pothole Detection

The MPU6050 is used to measure vehicle acceleration.

The system monitors the Z-axis acceleration and triggers a pothole event
when the configured dynamic threshold is exceeded.

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
   ├── Normal → Continue Monitoring
   │
   └── Pothole Detected
              │
              ▼
          Read GPS
              │
              ▼
        Store to MicroSD
💾 Store-and-Forward Architecture

One of the key design features of the Mobile Node is the
Store-and-Forward mechanism.

Instead of continuously depending on an Internet connection, the device
separates road monitoring from cloud synchronization.

STATE 1 - SENSING
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
Store Lat/Lng/Time
to MicroSD
STATE 2 - SYNCHRONIZATION
Vehicle Stops
      │
      ▼
User starts Sync
      │
      ▼
Enable Wi-Fi
      │
      ▼
Read Local Data
      │
      ▼
Build JSON Payload
      │
      ▼
HTTP POST
      │
      ▼
Supabase

This architecture allows the Mobile Node to continue collecting road-event
data even when network connectivity is temporarily unavailable.

The firmware uses a local file-buffering approach to preserve unsynchronized
data during the synchronization process.

🌊 Fixed Node

The Fixed Node is designed for permanent deployment at flood-prone
locations.

Hardware
ESP32
JSN-SR04T waterproof ultrasonic sensor
Solar panel
18650 Li-ion battery
Charging / power management circuitry
Main Functions
Measure water level.
Compare the measured distance with a reference base_distance.
Detect flooding when the calculated water level exceeds the configured
threshold.
Send incident alerts to the cloud.
Operate with periodic Deep Sleep to reduce power consumption.
Flood Detection
JSN-SR04T
    │
    ▼
Measure Distance
    │
    ▼
Compare with base_distance
    │
    ▼
Calculate Water Level
    │
    ├── Normal
    │
    └── Flood Threshold Exceeded
                 │
                 ▼
             Create JSON
                 │
                 ▼
            HTTPS / REST
                 │
                 ▼
              Supabase
⚡ Low-Power Design

The Fixed Node uses a periodic wake-up and Deep Sleep strategy.

┌──────────────────────┐
│      Deep Sleep      │
│                      │
│      Low Power       │
└──────────┬───────────┘
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
Measured Power Consumption
State	Current
Active / Wi-Fi transmission	~120 mA
Deep Sleep	~15 µA
Average current	~1.35 mA

The tested sleep cycle uses a total period of approximately 900 seconds,
with the majority of the cycle spent in Deep Sleep.

☁️ Cloud Architecture

RODA uses Supabase as the cloud backend.

Main Components
PostgreSQL Database
REST API
Supabase Storage
HTTPS communication
JSON data payloads
Data Flow
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
  │
  ├── Storage
  │
  └── API
       │
       ├── Web
       ├── Android
       └── AI Service

Each IoT device is identified using a unique device_id.

Example payload:

{
  "device_id": "NODE_CODINH_01",
  "lat": 10.732,
  "lng": 106.721,
  "type": "Flood",
  "status": "approved",
  "description": "Flood detected",
  "created_at": "2026-05-18T14:30:00+07:00"
}
🤖 AI Incident Verification

RODA integrates an AI-based image verification pipeline.

Technology
Python
YOLOv8
Hugging Face
Supported Incident Types
Pothole
Flood
Construction
AI Pipeline
Android / Web
     │
     ▼
Upload Incident Image
     │
     ▼
AI Service
     │
     ▼
YOLOv8 Inference
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
Update Cloud Status

The AI service assists in verifying user-submitted incident reports before
they are displayed as verified incidents.

🗺️ GIS Platform

RODA provides an interactive GIS interface for visualizing road incidents.

Technologies
OpenStreetMap
Leaflet.js
Turf.js
OSRM
Overpass API
OSMDroid
GIS Features
Interactive incident map
Incident markers
Geographic coordinates
Road visualization
Route calculation
Road snapping / spatial processing
Incident filtering
Road-condition visualization

Example:

                RODA GIS MAP


       ┌───────────────────────────────┐
       │       🚧 Construction         │
       │                               │
       │             🕳️ Pothole       │
       │                               │
       │   🌊 Flood                    │
       │                               │
       │        ─── Road ───           │
       │                               │
       └───────────────────────────────┘
📱 Android Application

The Android application provides a mobile interface for interacting with
the RODA platform.

Main Functions
Report road incidents.
Capture incident images.
Obtain geographic location.
View road incidents on a map.
Access cloud data.
View incident information.
Use routing and GIS features.
Submit images for AI verification.
Technologies
Java
Android SDK
OSMDroid
OkHttp
🌐 Web Dashboard

The Web Dashboard provides a centralized interface for monitoring road
conditions and managing incident data.

Main Functions
GIS incident visualization
Incident management
Device monitoring
Road-event information
Incident filtering
Data visualization
Administrative operations
Technologies
HTML
CSS
JavaScript
Leaflet.js
OpenStreetMap
REST API
🔌 Communication Interfaces

RODA uses multiple communication interfaces at different system layers.

Interface	Usage
I²C	ESP32 ↔ MPU6050
UART	ESP32 ↔ GPS
SPI	ESP32 ↔ MicroSD
Wi-Fi	ESP32 ↔ Internet
HTTPS	IoT Node ↔ Cloud
REST API	Cloud ↔ Applications
JSON	Data exchange format
🧪 Testing & Results
Mobile Node

The Store-and-Forward mechanism was tested under different network
conditions.

The tested scenarios successfully preserved and synchronized recorded
incident data without observed data loss.

Test Scenario	Result
Vehicle at 30 km/h	10/10 events synchronized
Vehicle at 50 km/h	7/7 events synchronized
Network unavailable	15/15 events synchronized
Observed data loss	0%
Fixed Node

JSN-SR04T testing produced measured errors between approximately:

0.1 cm – 0.3 cm

Scenario	Actual	Measured	Error
Dry road	100 cm	99.8 cm	0.2 cm
Water rising	98 cm	98.1 cm	0.1 cm
Light flooding	94 cm	94.3 cm	0.3 cm
Deep flooding	79 cm	79.2 cm	0.2 cm
Power Consumption

The Fixed Node achieved:

Active current: ~120 mA
Deep Sleep current: ~15 µA
Average current: ~1.35 mA

This demonstrates the effectiveness of the periodic Deep Sleep strategy
for low-power outdoor IoT deployment.

🧰 Technology Stack
Embedded
C / C++
ESP32
MPU6050
JSN-SR04T
NEO-6M GPS
MicroSD
18650 Li-ion
TP4056
I²C
UART
SPI
Wi-Fi
Deep Sleep
Cloud
Supabase
PostgreSQL
REST API
HTTPS
JSON
Supabase Storage
AI
Python
YOLOv8
Hugging Face
GIS
OpenStreetMap
Leaflet.js
Turf.js
OSRM
Overpass API
OSMDroid
Applications
Java
Android SDK
HTML
CSS
JavaScript
Vercel
📁 Repository Structure
RODA/
│
├── hardware/
│   ├── NodeDiDong/
│   │   └── ...
│   │
│   ├── NodeCoDinh/
│   │   └── ...
│   │
│   └── schematics/
│       ├── mobile_node/
│       └── fixed_node/
│
├── android/
│   └── ...
│
├── web/
│   └── ...
│
├── ai/
│   └── ...
│
├── images/
│   ├── logo_main.png
│   ├── roda-overview.png
│   ├── system-architecture.png
│   ├── mobile-node.jpg
│   ├── fixed-node.jpg
│   ├── web-dashboard.png
│   └── android-app.png
│
└── README.md
📸 Project Gallery
Mobile Node
<p align="center"> <img src="images/mobile-node.jpg" width="700"> </p>
Fixed Node
<p align="center"> <img src="images/fixed-node.jpg" width="700"> </p>
Web GIS Dashboard
<p align="center"> <img src="images/web-dashboard.png" width="850"> </p>
Android Application
<p align="center"> <img src="images/android-app.png" width="350"> </p>
🎥 Demo

Demo video will be added.

📚 Documentation

The complete graduation thesis contains detailed information about:

System architecture
Embedded hardware design
Firmware architecture
Sensor processing
Store-and-Forward mechanism
Fixed-node power management
Cloud architecture
Database design
GIS processing
AI verification
Android application
Web Dashboard
Testing and evaluation
👨‍💻 Author

Hen

Embedded Systems & IoT Developer
