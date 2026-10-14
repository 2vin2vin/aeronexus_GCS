import { WS_BASE_URL } from "@/config/env.js"
import { ConnectionState } from "./telemetryTypes.js"

/**
 * Production WebSocket Client for DroneIQ Real-Time Telemetry
 * Implements First-Message In-Band Authentication, 30s PING keepalive,
 * and exponential backoff reconnection.
 */
export class DroneIqClient {
  constructor() {
    this.ws = null
    const cleanWsBase = (WS_BASE_URL || "")
      .replace(/\/ws\/telemetry\/?$/i, "")
      .replace(/\/api\/?$/i, "")
      .replace(/\/+$/, "")
    this.url = `${cleanWsBase}/ws/telemetry`

    this.reconnectTimeoutId = null
    this.reconnectAttempts = 0
    this.maxReconnectDelay = 30000
    this.intentionalClose = false
    this.connectionState = ConnectionState.DISCONNECTED

    // Subscriptions & listeners
    this.telemetryListeners = new Set()
    this.waypointListeners = new Set()
    this.stateListeners = new Set()
    this.subscribedDrones = new Set()
    this.subscribeAllMode = true
  }

  /**
   * Register listener for live telemetry updates
   */
  onTelemetry(callback) {
    this.telemetryListeners.add(callback)
    return () => this.telemetryListeners.delete(callback)
  }

  onWaypoints(callback) {
    this.waypointListeners.add(callback)
    return () => this.waypointListeners.delete(callback)
  }

  /**
   * Register listener for connection state transitions
   */
  onStateChange(callback) {
    this.stateListeners.add(callback)
    callback(this.connectionState)
    return () => this.stateListeners.delete(callback)
  }

  setConnectionState(newState) {
    if (this.connectionState !== newState) {
      this.connectionState = newState
      this.stateListeners.forEach((cb) => {
        try {
          cb(newState)
        } catch (err) {
          console.error("[DroneIqClient] State listener error:", err)
        }
      })
    }
  }

  /**
   * Connect WebSocket with JWT token
   * @param {string} token 
   */
  connect(token) {
    if (!token) {
      console.warn("[DroneIqClient] Connect called without JWT token.")
      return
    }

    this.token = token
    this.intentionalClose = false

    // Avoid duplicate connection if already open or connecting
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return
    }

    this.clearTimers()
    this.setConnectionState(ConnectionState.CONNECTING)

    let socket
    try {
      socket = new WebSocket(this.url)
      this.ws = socket
    } catch (err) {
      console.error("[DroneIqClient] Failed to construct WebSocket:", err)
      this.setConnectionState(ConnectionState.BACKEND_UNAVAILABLE)
      this.scheduleReconnect()
      return
    }

    socket.onopen = () => {
      if (this.ws !== socket || this.intentionalClose) return
      console.info("[DroneIqClient] WebSocket connected to", this.url)
      this.setConnectionState(ConnectionState.CONNECTED)
      this.reconnectAttempts = 0

      // Step 2: In-band first-message authentication (must be within 5 seconds)
      this.send({
        type: "AUTH",
        token: this.token,
      })

      // Start 30s keepalive ping
      this.pingIntervalId = setInterval(() => {
        this.send({ type: "PING" })
      }, 30000)
    }

    socket.onmessage = (event) => {
      if (this.ws !== socket || this.intentionalClose) return
      try {
        const message = JSON.parse(event.data)

        switch (message.type) {
          case "AUTH_SUCCESS":
            console.info("[DroneIqClient] Authenticated successfully as", message.username)
            this.setConnectionState(ConnectionState.AUTHENTICATED)

            // Re-apply any specific drone subscriptions
            if (this.subscribeAllMode) {
              this.subscribeAll()
            } else {
              this.subscribedDrones.forEach((droneId) => {
                this.subscribeDrone(droneId)
              })
            }
            break

          case "TELEMETRY":
            if (message.data) {
              const telemetryPayload = {
                ...message.data,
                droneId: message.droneId || message.data.droneId,
                liveDroneConnected: message.liveDroneConnected ?? message.data.liveDroneConnected,
              }
              this.telemetryListeners.forEach((listener) => {
                try {
                  listener(telemetryPayload)
                } catch (err) {
                  console.error("[DroneIqClient] Telemetry listener error:", err)
                }
              })
            }
            break

          case "WAYPOINTS":
            if (message.data) {
              this.waypointListeners.forEach((listener) => {
                try {
                  listener({
                    ...message.data,
                    droneId: message.droneId || message.data.droneId,
                  })
                } catch (err) {
                  console.error("[DroneIqClient] Waypoint listener error:", err)
                }
              })
            }
            break

          case "PONG":
            // Keepalive acknowledged
            break

          case "AUTH_FAILURE":
            console.error("[DroneIqClient] Authentication failure:", message.message)
            this.setConnectionState(ConnectionState.AUTH_FAILURE)
            break

          default:
            // Unhandled custom message
            break
        }
      } catch (err) {
        console.error("[DroneIqClient] Error parsing message:", err)
      }
    }

    socket.onclose = (event) => {
      if (this.ws !== socket) return
      console.warn("[DroneIqClient] WebSocket closed. Code:", event.code, "Reason:", event.reason)
      this.ws = null
      this.clearTimers()

      if (!this.intentionalClose) {
        if (event.code === 1008) {
          this.setConnectionState(ConnectionState.AUTH_FAILURE)
        } else {
          this.setConnectionState(
            this.reconnectAttempts > 3 ? ConnectionState.BACKEND_UNAVAILABLE : ConnectionState.DISCONNECTED
          )
        }
        this.scheduleReconnect()
      } else {
        this.setConnectionState(ConnectionState.DISCONNECTED)
      }
    }

    socket.onerror = () => {
      if (this.ws !== socket || this.intentionalClose) return
      console.warn("[DroneIqClient] WebSocket error event encountered.")
      this.setConnectionState(
        this.reconnectAttempts > 2 ? ConnectionState.BACKEND_UNAVAILABLE : ConnectionState.CONNECTING
      )
    }
  }

  scheduleReconnect() {
    if (this.intentionalClose || this.reconnectTimeoutId) return

    this.reconnectAttempts++
    const delay = Math.min(1000 * Math.pow(1.5, this.reconnectAttempts), this.maxReconnectDelay)
    console.info(`[DroneIqClient] Scheduling reconnect attempt ${this.reconnectAttempts} in ${Math.round(delay / 1000)}s...`)

    this.reconnectTimeoutId = setTimeout(() => {
      this.reconnectTimeoutId = null
      if (this.token && !this.intentionalClose) {
        this.connect(this.token)
      }
    }, delay)
  }

  clearTimers() {
    if (this.pingIntervalId) {
      clearInterval(this.pingIntervalId)
      this.pingIntervalId = null
    }
    if (this.reconnectTimeoutId) {
      clearTimeout(this.reconnectTimeoutId)
      this.reconnectTimeoutId = null
    }
  }

  subscribeDrone(droneId) {
    if (!droneId) return
    this.subscribedDrones.add(droneId)
    this.subscribeAllMode = false
    this.send({ type: "SUBSCRIBE", droneId })
  }

  unsubscribeDrone(droneId) {
    if (!droneId) return
    this.subscribedDrones.delete(droneId)
    this.send({ type: "UNSUBSCRIBE", droneId })
  }

  subscribeAll() {
    this.subscribeAllMode = true
    this.send({ type: "SUBSCRIBE_ALL" })
  }

  disconnect() {
    this.intentionalClose = true
    this.clearTimers()
    this.token = null
    if (this.ws) {
      const socket = this.ws
      this.ws = null
      try {
        socket.close(1000, "User logged out")
      } catch {
        // Ignore
      }
    }
    this.setConnectionState(ConnectionState.DISCONNECTED)
  }

  send(data) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      try {
        this.ws.send(JSON.stringify(data))
      } catch (err) {
        console.error("[DroneIqClient] Failed to send WebSocket message:", err)
      }
    }
  }
}

// Export singleton instance for app-wide use
export const telemetryClient = new DroneIqClient()
export default telemetryClient
