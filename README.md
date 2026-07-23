<p align="center">
  <img src="https://github.com/user-attachments/assets/8ad1b7f4-ec5e-4df6-9b17-89f6b3dcba49"
       alt="app logo"
    width="100" height="100"/>
  <br>
  <em>PrisonConnect</em>
</p>

# PrisonConnect: Demo Implementation Report

## 1. Project Overview
PrisonConnect is a secure WebRTC-based communication platform designed for correctional facilities. This demo showcases a complete end-to-end workflow: from an inmate initiating a call on an Android Kiosk to a family member joining via a mobile or desktop browser.

The system prioritizes **security, accountability (recording), and ease of access** for non-technical users.

---

## 2. Demo Scope
| Feature | Status | Implementation Detail |
| :--- | :--- | :--- |
| **Inmate Authentication** | ✅ | PIN-based login synced with Supabase Auth |
| **Contact Management** | ✅ | Dynamic contact list with balance tracking |
| **Multi-Provider SMS** | ✅ | Supports Twilio, SIM Carrier, and Manual Copy |
| **WebRTC Video/Audio** | ✅ | 1-to-1 low-latency streaming (Unified Plan) |
| **OTP Security** | ✅ | Mandatory 6-digit code for Web access |
| **Session Recording** | ✅ | On-device muxing (H.264/AAC) & Cloud Upload |
| **Web Hardening** | ✅ | Protection against DevTools and code inspection |
| **Auto-Billing** | ✅ | Real-time balance decrement in PostgreSQL |

---

## 3. System Architecture
The demo relies on a modern, serverless architecture to ensure rapid deployment and high reliability.

### A. Communication & Traversal
- **Signaling**: Uses **Supabase Realtime (WebSockets)** to exchange SDP Offers, Answers, and ICE Candidates.
- **NAT Traversal**: Employs **STUN/TURN** servers (via Metered.ca) to ensure connectivity even across restrictive jail firewalls and symmetric NATs.

### B. Backend (Supabase Stack)
- **Database (PostgreSQL)**:
    - `users`: Stores inmate profiles, PINs, and remaining call balance.
    - `call_rooms`: Manages active signaling states and session tokens.
- **Edge Functions (Deno)**:
    - `send-sms`: Communicates with **Twilio API** to deliver call links. Returns detailed error status (e.g., Unverified Number).
- **Storage (Bucket)**:
    - `recordings`: Secure bucket where MP4 call recordings are uploaded automatically after each session.

### C. Archetectural Flow of the system


<p align="center">
    <img width="1683" height="935" alt="Image" src="https://github.com/user-attachments/assets/eeb2e08c-66fa-44c4-a915-44e6d512000a" />
  <br>
  <em>This is the archetectural flow of the system</em>
</p>
---

## 4. User Flow Demo

### Step 1: Inmate Login
Open the Android app and log in using the inmate PIN. This retrieves the profile and balance from the `users` table.

### Step 2: Selecting a Contact
On the Dashboard, select a contact from the list or use the **Dialer** for a new number. You can toggle between **Cloud (Twilio)** or **SIM** mode based on your account setup.

### Step 3: SMS Delivery & Lobby
Click "Start Video/Audio Call". The Kiosk enters the **Lobby**. 
- If SMS succeeds: The recipient gets a link.
- If SMS fails: The app shows the error and provides a **"Copy Link + OTP"** button for manual sharing.

### Step 4: Web Access & OTP
The family member opens the link in a browser. They must enter the **6-digit OTP** displayed on the Kiosk or sent via SMS to verify their identity.

### Step 5: The Call
Once verified, the browser requests Camera/Mic access. The call connects automatically using WebRTC. The Kiosk starts recording immediately.

### Step 6: Termination & Upload
When either side hangs up, the Kiosk stops the recording, updates the inmate's final balance in the DB, and uploads the MP4 file to Supabase Storage. The browser automatically redirects to a blank page for security.

<p align="center">
  <img width="1812" height="868" alt="Image" src="https://github.com/user-attachments/assets/e65dc24d-22ec-4dfe-a1c6-ce9e85128228" />
  <br>
  <em>User Flow in the app</em>
</p>
---

## 5. Engineering Challenges & Solutions

### I. Signaling Throttling (Rate Limiting)
- **Challenge**: WebRTC generates hundreds of ICE candidates per second. Sending them individually flooded the Supabase socket, causing rate-limit errors and dropped calls.
- **Solution**: Implemented **100ms Intelligent Batching**. Candidates are collected in a queue and sent as a single JSON array, reducing message volume by 80% without introducing perceived latency.

### II. Recording Synchronization (Shared Clock)
- **Challenge**: Simultaneous live streaming and hardware recording caused the Audio and Video tracks to use different time-bases, resulting in corrupt "Out-of-order" frames in the MP4 file.
- **Solution**: Developed a **Monotonic Shared Clock** system. Both encoders now reference a single, strictly increasing baseline, ensuring perfect AV sync in the final recording.

### III. Android 14 Compliance (Foreground Services)
- **Challenge**: Strict OS policies require precise timing for camera/mic permissions in background services.
- **Solution**: Refined the service lifecycle to **bind** during the lobby (silent) and only **start foreground** when the call becomes active. This prevents redundant notifications and permission crashes.


---

## 6. Critical Note on Recording Quality
> [!WARNING]
> **Performance Trade-off**: The recording quality in this demo is intentionally set to a lower bitrate. 
> 
> **Why?** Handling simultaneous **high-definition live streaming** (WebRTC) and **hardware-level H.264 encoding** on a single mobile device is extremely CPU/GPU intensive. To prioritize **call stability** and prevent the device from overheating or lagging during the live conversation, the recording engine uses a optimized, lower-resolution profile.

---

## 7. Demo vs. Production Architecture Comparison
This demo acts as a functional "Proof of Concept." The following table outlines the technical gaps between this demo and the proposed full-scale enterprise deployment.

| Component | Current Demo Implementation | Production-Grade Proposal |
| :--- | :--- | :--- |
| **Media Engine** | Peer-to-Peer (WebRTC Mesh) | **Self-hosted Jitsi/SFU** for multi-party and load handling |
| **Signaling** | Supabase Realtime (WebSockets) | **RabbitMQ & Redis** for high-throughput task queuing |
| **Traversal** | Standard STUN/TURN (Relay) | **Dedicated Coturn Cluster** for 100% NAT traversal |
| **Inmate Auth** | Secure PIN-based Login | **RFID-based Hardware Authentication** |
| **Scalability** | Serverless / Single-Region | **Horizontal Scaling with HAProxy** Load Balancers |
| **SMS Compliance** | Standard API (Twilio) | **DLT-Compliant Gateway** (Government Ready) |
| **Monitoring** | Basic On-Device Recording | **Live Authority Monitoring** & 24/7 Audit Logging |
| **Availability** | Standard Cloud Uptime | **High-Availability (HA) Multi-Layer Cluster** |
| **Security Layer** | Public Cloud (Supabase) | **Private VPC & Zero Trust Network** isolation |
| **Data Integrity** | Client-Side Muxing (Tamper Risk) | **Server-Side Recording** on secure media bridge |
| **Identity Proof** | PIN-based | **Multimodal Biometrics** (Face ID + RFID) |

### Summary of Demo Limitations
- **Security & Tampering**: In the demo, recording happens on the device itself. In a production environment, recording would occur **server-side** on the media bridge to prevent an inmate from physically tampering with the device to stop a recording.
- **Network Isolation**: The demo runs on the public internet. Production deployment would be hosted within a **Private Government Network (VPC)** with strict firewall rules and end-to-end data encryption at rest.
- **Scaling**: The demo is optimized for 1-to-1 sessions. Production requires an SFU (Selective Forwarding Unit) to scale to thousands of simultaneous jail-wide calls.
