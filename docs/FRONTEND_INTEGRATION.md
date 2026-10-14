# DroneIQ Frontend Integration Guide

This guide provides frontend engineers with everything needed to integrate with the DroneIQ Ground Control Station (GCS) backend.

---

## 1. Authentication Flow (REST)

All REST endpoints (except `/api/auth/login` and `/api/auth/register`) require a standard HTTP Bearer token.

### Login Request

```http
POST /api/auth/login HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{
  "username": "admin",
  "password": "admin123"
}
```

### Login Response

```json
{
  "success": true,
  "message": "Authentication successful",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400000,
    "username": "admin",
    "email": "admin@droneiq.io",
    "role": "ADMIN"
  },
  "timestamp": "2026-09-09T10:30:00Z"
}
```

### Authenticated REST Requests

Include the token in the `Authorization` header:

```http
GET /api/drones HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## 2. Real-Time Telemetry via WebSocket (`/ws/telemetry`)

Standard browser WebSocket implementations (`new WebSocket(...)`) cannot set custom HTTP request headers during the initial HTTP upgrade handshake. To maintain strict security and avoid exposing JWT tokens in URL query strings (which leak into access logs, browser histories, and proxy logs), DroneIQ implements **First-Message In-Band Authentication**.

### Connection Protocol

```
Browser / Frontend                           DroneIQ Backend
       |                                             |
       |  1. Connect: ws://localhost:8080/ws/telemetry |
       |-------------------------------------------->|
       |                                             | (5s auth timeout starts)
       |  2. Send Auth: {"type":"AUTH","token":"..."}|
       |-------------------------------------------->|
       |                                             | (Validates JWT)
       |  3. Ack: {"type":"AUTH_SUCCESS", ...}       |
       |<--------------------------------------------| (Auto-subscribes to all)
       |                                             |
       |  4. Live Telemetry stream                   |
       |<--------------------------------------------|
       |     {"type":"TELEMETRY","droneId":"...",...}|
       |                                             |
       |  5. Optional: Subscribe to specific drone   |
       |     {"type":"SUBSCRIBE","droneId":"DRONE-001"}|
       |-------------------------------------------->|
       |  6. Ack: {"type":"SUBSCRIBED", ...}         |
       |<--------------------------------------------|
       |                                             |
       |  7. Keepalive Ping: {"type":"PING"}         |
       |-------------------------------------------->|
       |  8. Pong: {"type":"PONG"}                   |
       |<--------------------------------------------|
```

> [!WARNING]
> If a client connects and does not send a valid `AUTH` message within **5 seconds**, the server automatically closes the connection with code `1008 (POLICY_VIOLATION)`.

### Client Outbound Messages (Frontend -> Server)

#### Authenticate (Required as First Message)
```json
{
  "type": "AUTH",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

#### Subscribe to Specific Drone
```json
{
  "type": "SUBSCRIBE",
  "droneId": "DRONE-001"
}
```

#### Unsubscribe from Specific Drone
```json
{
  "type": "UNSUBSCRIBE",
  "droneId": "DRONE-001"
}
```

#### Subscribe to All Drones (Fleet Overview)
```json
{
  "type": "SUBSCRIBE_ALL"
}
```

#### Heartbeat / Ping
```json
{
  "type": "PING"
}
```

---

### Server Inbound Messages (Server -> Frontend)

#### Authentication Success
```json
{
  "type": "AUTH_SUCCESS",
  "username": "admin",
  "message": "Authenticated successfully. Subscribed to all drone telemetry by default."
}
```

#### Authentication Failure (Connection will close)
```json
{
  "type": "AUTH_FAILURE",
  "message": "Invalid or expired token"
}
```

#### Telemetry Event
```json
{
  "type": "TELEMETRY",
  "droneId": "DRONE-001",
  "liveDroneConnected": true,
  "data": {
    "droneId": "DRONE-001",
    "timestamp": "2026-09-09T10:30:15.123Z",
    "latitude": 37.414251,
    "longitude": -122.054128,
    "altitude": 75.20,
    "heading": 142.5,
    "speed": 12.50,
    "roll": 4.12,
    "pitch": -1.50,
    "yaw": 142.5,
    "batteryPercentage": 97.4,
    "gpsSatellites": 16,
    "flightMode": "AUTO",
    "armed": true,
    "status": "OK",
    "metadata": {
      "simulator": true,
      "engineTempC": 42.5
    }
  }
}
```

---

## 3. Production Frontend TypeScript / JavaScript Client

You can drop the following class into your React, Vue, or Angular application:

```typescript
export interface TelemetryData {
  droneId: string;
  timestamp: string;
  latitude: number;
  longitude: number;
  altitude: number;
  heading: number;
  speed: number;
  roll?: number;
  pitch?: number;
  yaw?: number;
  batteryPercentage: number;
  gpsSatellites: number;
  flightMode: string;
  armed: boolean;
  status: string;
}

export class DroneIqClient {
  private ws: WebSocket | null = null;
  private token: string;
  private url: string;
  private pingIntervalId: any = null;
  private onTelemetryCallback: (data: TelemetryData) => void;

  constructor(
    wsUrl: string,
    token: string,
    onTelemetry: (data: TelemetryData) => void
  ) {
    this.url = wsUrl;
    this.token = token;
    this.onTelemetryCallback = onTelemetry;
  }

  public connect(): void {
    this.ws = new WebSocket(this.url);

    this.ws.onopen = () => {
      console.log("[DroneIQ] WebSocket opened, sending AUTH...");
      this.send({ type: "AUTH", token: this.token });

      // Start 30s keepalive ping
      this.pingIntervalId = setInterval(() => {
        this.send({ type: "PING" });
      }, 30000);
    };

    this.ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      switch (message.type) {
        case "AUTH_SUCCESS":
          console.log("[DroneIQ] Authenticated successfully as", message.username);
          break;
        case "TELEMETRY":
          this.onTelemetryCallback(message.data as TelemetryData);
          break;
        case "PONG":
          // Heartbeat acknowledged
          break;
        case "AUTH_FAILURE":
          console.error("[DroneIQ] Authentication failed:", message.message);
          break;
        default:
          console.log("[DroneIQ] Unhandled message:", message);
      }
    };

    this.ws.onclose = (event) => {
      console.warn("[DroneIQ] WebSocket closed:", event.code, event.reason);
      clearInterval(this.pingIntervalId);
      // Implement exponential backoff reconnect here
    };

    this.ws.onerror = (err) => {
      console.error("[DroneIQ] WebSocket error:", err);
    };
  }

  public subscribeDrone(droneId: string): void {
    this.send({ type: "SUBSCRIBE", droneId });
  }

  public subscribeAll(): void {
    this.send({ type: "SUBSCRIBE_ALL" });
  }

  public disconnect(): void {
    if (this.ws) {
      clearInterval(this.pingIntervalId);
      this.ws.close();
    }
  }

  private send(data: any): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    }
  }
}
```
