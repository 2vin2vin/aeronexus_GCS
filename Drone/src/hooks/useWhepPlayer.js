import { useState, useEffect, useRef, useCallback } from "react"

const RETRY_DELAYS = [1000, 2000, 4000, 10000]
const MAX_AUTO_RETRIES = 4

/**
 * WebSocket-based WebRTC player.
 *
 * Assumed signaling protocol:
 *   Client -> { type: "join", room: "GCS" }
 *   Client -> { type: "offer", room: "GCS", sdp: "..." }
 *   Server -> { type: "answer", room: "GCS", sdp: "..." }
 *   Both   -> { type: "candidate", room: "GCS", candidate: {...} }
 *
 * IMPORTANT:
 * The join and SDP message formats above are assumptions.
 * Adapt them to match your signaling server's actual protocol.
 */
export function useWhepPlayer({
  webrtcUrl,
  enabled = true,
  onStatusChange,
  room = "GCS",
} = {}) {
  const videoRef = useRef(null)
  const pcRef = useRef(null)
  const wsRef = useRef(null)
  const retryTimeoutRef = useRef(null)
  const retryCountRef = useRef(0)
  const isUnmountedRef = useRef(false)
  const activeNegotiationIdRef = useRef(0)
  const connectRef = useRef(null)

  const [isPlaying, setIsPlaying] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState(null)
  const [connectionState, setConnectionState] = useState("idle")

  const updateState = useCallback(
    (state, err = null) => {
      if (isUnmountedRef.current) return

      setConnectionState(state)
      setError(err)
      onStatusChange?.(state, err)
    },
    [onStatusChange]
  )

  const cleanup = useCallback(() => {
    if (retryTimeoutRef.current) {
      clearTimeout(retryTimeoutRef.current)
      retryTimeoutRef.current = null
    }

    const ws = wsRef.current
    wsRef.current = null

    if (ws) {
      ws.onopen = null
      ws.onmessage = null
      ws.onerror = null
      ws.onclose = null

      if (
        ws.readyState === WebSocket.CONNECTING ||
        ws.readyState === WebSocket.OPEN
      ) {
        ws.close()
      }
    }

    const pc = pcRef.current
    pcRef.current = null

    if (pc) {
      pc.ontrack = null
      pc.onicecandidate = null
      pc.onconnectionstatechange = null
      pc.oniceconnectionstatechange = null

      try {
        pc.close()
      } catch {
        // Ignore teardown errors.
      }
    }

    if (videoRef.current) {
      videoRef.current.srcObject = null
    }

    setIsPlaying(false)
  }, [])

  const scheduleReconnect = useCallback(
    (reason) => {
      cleanup()

      if (isUnmountedRef.current) return

      if (!enabled || !webrtcUrl) {
        updateState("offline")
        setIsLoading(false)
        return
      }

      if (retryCountRef.current >= MAX_AUTO_RETRIES) {
        updateState("offline", "Camera stream currently offline")
        setIsLoading(false)
        return
      }

      const delay = RETRY_DELAYS[retryCountRef.current] ?? 10000
      retryCountRef.current += 1

      if (import.meta.env?.DEV) {
        console.warn(
          `[WebRTC-WS] ${reason}. Retrying in ${delay}ms ` +
          `(${retryCountRef.current}/${MAX_AUTO_RETRIES})`
        )
      }

      updateState("reconnecting", "Connection lost. Retrying...")
      setIsLoading(true)

      retryTimeoutRef.current = setTimeout(() => {
        if (!isUnmountedRef.current) {
          connectRef.current?.()
        }
      }, delay)
    },
    [cleanup, enabled, webrtcUrl, updateState]
  )

  const connect = useCallback(async () => {
    cleanup()

    if (isUnmountedRef.current) return

    if (!enabled || !webrtcUrl) {
      updateState("idle")
      setIsLoading(false)
      return
    }

    const id = ++activeNegotiationIdRef.current

    setIsLoading(true)
    updateState("connecting")

    if (
      typeof RTCPeerConnection === "undefined" ||
      typeof WebSocket === "undefined"
    ) {
      updateState("failed", "WebRTC or WebSocket is not supported")
      setIsLoading(false)
      return
    }

    let pc

    try {
      if (import.meta.env?.DEV) {
        console.log(`[WebRTC-WS] [${id}] Connecting to ${webrtcUrl}`)
      }

      const ws = new WebSocket(webrtcUrl)
      wsRef.current = ws

      pc = new RTCPeerConnection({
        iceServers: [
          { urls: "stun:stun.l.google.com:19302" },
        ],
        bundlePolicy: "max-bundle",
      })

      pcRef.current = pc

      // Receive video from the remote peer.
      pc.addTransceiver("video", { direction: "recvonly" })

      // If the stream includes audio, this enables receiving it too.
      pc.addTransceiver("audio", { direction: "recvonly" })

      const isCurrent = () =>
        !isUnmountedRef.current &&
        activeNegotiationIdRef.current === id &&
        pcRef.current === pc &&
        wsRef.current === ws

      const send = (message) => {
        if (ws.readyState !== WebSocket.OPEN) {
          console.warn("[WebRTC-WS] Cannot send: WebSocket is not open")
          return
        }

        ws.send(JSON.stringify({
          room,
          ...message,
        }))
      }

      pc.ontrack = (event) => {
        if (!isCurrent()) return

        const video = videoRef.current
        if (!video) return

        const stream =
          event.streams?.[0] ??
          (() => {
            const s = video.srcObject instanceof MediaStream
              ? video.srcObject
              : new MediaStream()

            if (!s.getTracks().some((t) => t.id === event.track.id)) {
              s.addTrack(event.track)
            }

            return s
          })()

        video.srcObject = stream
        video.play().catch((err) => {
          if (import.meta.env?.DEV) {
            console.debug("[WebRTC-WS] Video autoplay blocked:", err)
          }
        })

        setIsPlaying(true)
        setIsLoading(false)
        updateState("connected")
        retryCountRef.current = 0
      }

      pc.onicecandidate = (event) => {
        if (!isCurrent() || !event.candidate) return

        // Matches the observed candidate message structure.
        send({
          type: "candidate",
          candidate: event.candidate.toJSON(),
        })
      }

      pc.onconnectionstatechange = () => {
        if (!isCurrent()) return

        const state = pc.connectionState

        if (import.meta.env?.DEV) {
          console.log(`[WebRTC-WS] [${id}] Connection: ${state}`)
        }

        if (state === "connected") {
          setIsLoading(false)
          updateState("connected")
          retryCountRef.current = 0
        } else if (state === "failed" || state === "disconnected") {
          scheduleReconnect(`Peer connection ${state}`)
        }
      }

      pc.oniceconnectionstatechange = () => {
        if (!isCurrent()) return

        if (pc.iceConnectionState === "failed") {
          scheduleReconnect("ICE connection failed")
        }
      }

      ws.onopen = async () => {
        if (!isCurrent()) return

        try {
          if (import.meta.env?.DEV) {
            console.log(`[WebRTC-WS] [${id}] WebSocket connected`)
          }

          /*
           * IMPORTANT:
           * Confirm the required join message with your server.
           * Some servers use "join", others use "subscribe",
           * and some do not require a separate join message.
           */
          send({ type: "join" })

          // Assumption: this server expects the viewer to create
          // an offer. If it sends an offer instead, use the
          // incoming-offer branch in ws.onmessage.
          const offer = await pc.createOffer()

          if (!isCurrent()) return

          await pc.setLocalDescription(offer)

          if (!isCurrent()) return

          send({
            type: "offer",
            sdp: pc.localDescription.sdp,
          })

          if (import.meta.env?.DEV) {
            console.log(`[WebRTC-WS] [${id}] SDP offer sent`)
          }
        } catch (err) {
          if (!isCurrent()) return

          console.error("[WebRTC-WS] Offer negotiation failed:", err)
          scheduleReconnect("Offer negotiation failed")
        }
      }

      ws.onmessage = async (event) => {
        if (!isCurrent()) return

        try {
          const message = JSON.parse(event.data)

          if (import.meta.env?.DEV) {
            console.log("[WebRTC-WS] Received:", message)
          }

          // Handle an SDP answer from the server.
          if (message.type === "answer") {
            const sdp =
              typeof message.sdp === "string"
                ? message.sdp
                : message.sdp?.sdp

            if (!sdp) {
              throw new Error("Answer message has no SDP")
            }

            await pc.setRemoteDescription({
              type: "answer",
              sdp,
            })

            return
          }

          // Also support a server-initiated offer.
          if (message.type === "offer") {
            const sdp =
              typeof message.sdp === "string"
                ? message.sdp
                : message.sdp?.sdp

            if (!sdp) {
              throw new Error("Offer message has no SDP")
            }

            await pc.setRemoteDescription({
              type: "offer",
              sdp,
            })

            const answer = await pc.createAnswer()
            await pc.setLocalDescription(answer)

            if (!isCurrent()) return

            send({
              type: "answer",
              sdp: pc.localDescription.sdp,
            })

            return
          }

          // Handle ICE candidates received from the server.
          if (message.type === "candidate") {
            const candidate = message.candidate

            // An empty candidate can indicate end-of-candidates.
            if (!candidate || candidate === "") return

            // Some protocols wrap the actual candidate in
            // another "candidate" property.
            const iceCandidate =
              typeof candidate === "object" &&
              candidate.candidate &&
              typeof candidate.candidate === "object"
                ? candidate.candidate
                : candidate

            if (
              typeof iceCandidate === "object" &&
              iceCandidate.candidate === ""
            ) {
              return
            }

            await pc.addIceCandidate(iceCandidate)
          }
        } catch (err) {
          if (!isCurrent()) return

          console.error("[WebRTC-WS] Signaling message failed:", err)
          scheduleReconnect("Invalid signaling message")
        }
      }

      ws.onerror = () => {
        if (!isCurrent()) return

        scheduleReconnect("WebSocket error")
      }

      ws.onclose = () => {
        if (!isCurrent()) return

        scheduleReconnect("WebSocket closed")
      }
    } catch (err) {
      if (activeNegotiationIdRef.current !== id) return

      console.error("[WebRTC-WS] Connection setup failed:", err)
      scheduleReconnect("Connection setup failed")
    }
  }, [
    cleanup,
    enabled,
    webrtcUrl,
    room,
    updateState,
    scheduleReconnect,
  ])

  useEffect(() => {
    connectRef.current = connect
  }, [connect])

  const retry = useCallback(() => {
    retryCountRef.current = 0
    connectRef.current?.()
  }, [])

  useEffect(() => {
    isUnmountedRef.current = false
    retryCountRef.current = 0

    if (enabled && webrtcUrl) {
      const timer = setTimeout(() => {
        if (!isUnmountedRef.current) {
          connectRef.current?.()
        }
      }, 0)

      return () => {
        clearTimeout(timer)
        isUnmountedRef.current = true
        activeNegotiationIdRef.current += 1
        cleanup()
      }
    }

    updateState("idle")
    setIsLoading(false)

    return () => {
      isUnmountedRef.current = true
      activeNegotiationIdRef.current += 1
      cleanup()
    }
  }, [webrtcUrl, enabled, cleanup, updateState])

  return {
    videoRef,
    isPlaying,
    isLoading,
    error,
    connectionState,
    retry,
  }
}

export default useWhepPlayer