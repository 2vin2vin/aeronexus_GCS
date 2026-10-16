import json
import os
import queue
import threading
import time

from collections import deque

import requests
import websocket

from pymavlink import mavutil


# ============================================================
# CONFIGURATION
# ============================================================

SERVER_HOST = os.getenv("DRONEIQ_HOST", "localhost")
SERVER_PORT = int(os.getenv("DRONEIQ_PORT", "8081"))

HTTP_BASE_URL = f"http://{SERVER_HOST}:{SERVER_PORT}"
WS_URL = f"ws://{SERVER_HOST}:{SERVER_PORT}/ws/telemetry"
LOGIN_URL = f"{HTTP_BASE_URL}/api/auth/login"

# Use environment variables instead of hardcoding credentials.
USERNAME = os.getenv("DRONEIQ_USERNAME", "superadmin")
PASSWORD = os.getenv("DRONEIQ_PASSWORD", "admin123")

# This bridge is configured for ONE ArduPilot SITL vehicle.
DRONE_ID = os.getenv("DRONE_ID", "DRONE-001")

# Configure MAVProxy to forward this vehicle's MAVLink packets
# to UDP port 14560.
MAVLINK_CONNECTION = os.getenv(
    "MAVLINK_CONNECTION",
    "udpin:0.0.0.0:14553",
)

# Timeouts
HEARTBEAT_TIMEOUT = 30
COMMAND_ACK_TIMEOUT = 8
STATE_CONFIRM_TIMEOUT = 10

# Keep example takeoff requests within a conservative test range.
MIN_TAKEOFF_ALTITUDE = 1.0
MAX_TAKEOFF_ALTITUDE = 20.0


# ============================================================
# GLOBAL STATE
# ============================================================

JWT_TOKEN = None
ws_app = None

mav = None

# WebSocket connection and authentication state.
authenticated = threading.Event()
ws_send_lock = threading.Lock()

# MAVLink connection state.
mav_lock = threading.Lock()
state_condition = threading.Condition()

vehicle_state = {
    "heartbeat_received": False,
    "armed": None,
    "custom_mode": None,
    "relative_altitude_m": None,
    "last_heartbeat": None,
}

# A single MAVLink reader distributes ACKs and telemetry state.
ack_lock = threading.Lock()
ack_waiters = {}

# Process commands sequentially to avoid conflicting flight actions.
command_queue = queue.Queue()

# Remember recent command IDs to avoid duplicate dispatch.
recent_command_ids = deque(maxlen=1000)
recent_command_ids_set = set()
command_ids_lock = threading.Lock()


# ============================================================
# SPRING BOOT HTTP LOGIN
# ============================================================

def login():
    if not PASSWORD:
        raise RuntimeError(
            "DRONEIQ_PASSWORD is not set. "
            "Set it before starting the bridge."
        )

    print("Logging in to Spring Boot...")

    response = requests.post(
        LOGIN_URL,
        json={
            "username": USERNAME,
            "password": PASSWORD,
        },
        timeout=10,
    )

    print("Login HTTP status:", response.status_code)
    response.raise_for_status()

    response_json = response.json()
    data = response_json.get("data")

    if not isinstance(data, dict):
        raise RuntimeError(
            "Login response does not contain a valid 'data' object."
        )

    token = data.get("token")

    if not token:
        raise RuntimeError(
            "Login succeeded, but the JWT token was missing."
        )

    print("JWT received successfully. Token not displayed.")

    return token


# ============================================================
# WEBSOCKET SEND HELPERS
# ============================================================

def send_ws_message(message):
    """
    Send a JSON message safely.

    Returns False if the WebSocket is not open or sending fails.
    """
    if ws_app is None or not authenticated.is_set():
        return False

    try:
        with ws_send_lock:
            if ws_app.sock is None or not ws_app.sock.connected:
                return False

            ws_app.send(json.dumps(message))

        return True

    except Exception as exc:
        print("[WebSocket] Send failed:", exc)
        return False


def send_command_status(
    command_id,
    drone_id,
    status,
    detail=None,
):
    """
    Report command progress to Spring Boot.

    Backend support for COMMAND_ACK is required.
    """
    message = {
        "type": "COMMAND_ACK",
        "commandId": command_id,
        "droneId": drone_id,
        "status": status,
        "timestamp": time.time(),
    }

    if detail:
        message["message"] = str(detail)

    if not send_ws_message(message):
        print(
            f"[COMMAND] Could not report status {status} "
            f"for {command_id}"
        )


# ============================================================
# MAVLINK CONNECTION
# ============================================================

def connect_to_sitl():
    """
    Connect to ArduPilot SITL through a MAVLink UDP endpoint.

    Start SITL and configure the appropriate MAVProxy output first.
    """
    global mav

    print()
    print("=" * 55)
    print("Connecting to ArduPilot SITL")
    print("MAVLink endpoint:", MAVLINK_CONNECTION)
    print("=" * 55)

    mav = mavutil.mavlink_connection(
        MAVLINK_CONNECTION,
        autoreconnect=True,
    )

    heartbeat = mav.wait_heartbeat(
        timeout=HEARTBEAT_TIMEOUT,
    )

    if heartbeat is None:
        raise TimeoutError(
            "No MAVLink heartbeat received. "
            "Check SITL, MAVProxy output, and UDP port."
        )

    if heartbeat.type == mavutil.mavlink.MAV_TYPE_GCS:
        raise RuntimeError(
            "Received a GCS heartbeat instead of a vehicle heartbeat."
        )

    with state_condition:
        vehicle_state["heartbeat_received"] = True

        vehicle_state["armed"] = bool(
            heartbeat.base_mode
            & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED
        )

        vehicle_state["custom_mode"] = heartbeat.custom_mode
        vehicle_state["last_heartbeat"] = time.monotonic()

        state_condition.notify_all()

    print("MAVLink heartbeat received.")
    print("Target system:", mav.target_system)
    print("Target component:", mav.target_component)
    print("Initial armed state:", vehicle_state["armed"])

    # Start the only thread that reads MAVLink messages.
    threading.Thread(
        target=mavlink_reader,
        daemon=True,
        name="mavlink-reader",
    ).start()


# ============================================================
# MAVLINK READER
# ============================================================

def mavlink_reader():
    """
    Read MAVLink messages in one place.

    This prevents separate command threads from consuming each
    other's COMMAND_ACK or heartbeat messages.
    """
    print("[MAVLink] Reader started.")

    while True:
        try:
            message = mav.recv_match(
                blocking=True,
                timeout=1,
            )

            if message is None:
                continue

            message_type = message.get_type()

            if message_type == "BAD_DATA":
                continue

            # ------------------------------------------------
            # Heartbeat: armed state and flight mode
            # ------------------------------------------------

            if message_type == "HEARTBEAT":

                # Ignore other systems if the UDP endpoint carries
                # traffic from more than one MAVLink system.
                if (
                    mav.target_system
                    and message.get_srcSystem() != mav.target_system
                ):
                    continue

                with state_condition:
                    vehicle_state["heartbeat_received"] = True

                    vehicle_state["armed"] = bool(
                        message.base_mode
                        & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED
                    )

                    vehicle_state["custom_mode"] = message.custom_mode
                    vehicle_state["last_heartbeat"] = time.monotonic()

                    state_condition.notify_all()

            # ------------------------------------------------
            # Position: relative altitude
            # ------------------------------------------------

            elif message_type == "GLOBAL_POSITION_INT":

                if (
                    mav.target_system
                    and message.get_srcSystem() != mav.target_system
                ):
                    continue

                relative_altitude_m = message.relative_alt / 1000.0

                with state_condition:
                    vehicle_state["relative_altitude_m"] = (
                        relative_altitude_m
                    )

                    state_condition.notify_all()

            # ------------------------------------------------
            # Command ACK: deliver to matching waiter
            # ------------------------------------------------

            elif message_type == "COMMAND_ACK":

                command_id = message.command

                with ack_lock:
                    waiter = ack_waiters.get(command_id)

                if waiter is not None:
                    waiter.put(message)

                print(
                    f"[MAVLink] COMMAND_ACK command={command_id}, "
                    f"result={message.result}"
                )

        except Exception as exc:
            print("[MAVLink] Reader error:", exc)
            time.sleep(0.5)


# ============================================================
# STATE HELPERS
# ============================================================

def get_vehicle_state():
    with state_condition:
        return dict(vehicle_state)


def wait_for_state(predicate, timeout):
    """
    Wait for a state condition updated by the MAVLink reader.
    """
    deadline = time.monotonic() + timeout

    with state_condition:
        while True:
            snapshot = dict(vehicle_state)

            if predicate(snapshot):
                return snapshot

            remaining = deadline - time.monotonic()

            if remaining <= 0:
                raise TimeoutError(
                    f"Vehicle state was not confirmed within {timeout}s. "
                    f"Last state: {snapshot}"
                )

            state_condition.wait(timeout=remaining)


def ensure_fresh_heartbeat():
    """
    Fail safely if the vehicle heartbeat is stale.
    """
    state = get_vehicle_state()
    last_heartbeat = state.get("last_heartbeat")

    if last_heartbeat is None:
        raise RuntimeError("No vehicle heartbeat has been received.")

    age = time.monotonic() - last_heartbeat

    if age > 5:
        raise RuntimeError(
            f"Vehicle heartbeat is stale ({age:.1f}s old)."
        )


def is_armed():
    return get_vehicle_state().get("armed") is True


# ============================================================
# MAVLINK COMMAND HELPERS
# ============================================================

def send_command_long(
    command_id,
    param1=0,
    param2=0,
    param3=0,
    param4=0,
    param5=0,
    param6=0,
    param7=0,
    timeout=COMMAND_ACK_TIMEOUT,
):
    """
    Send COMMAND_LONG and wait for its matching ACK.

    Only the MAVLink reader receives messages. This function waits
    on a dedicated queue for the relevant command acknowledgement.
    """
    ensure_fresh_heartbeat()

    waiter = queue.Queue(maxsize=10)

    with ack_lock:
        ack_waiters[command_id] = waiter

    try:
        with mav_lock:
            mav.mav.command_long_send(
                mav.target_system,
                mav.target_component,
                command_id,
                0,
                param1,
                param2,
                param3,
                param4,
                param5,
                param6,
                param7,
            )

        deadline = time.monotonic() + timeout

        while True:
            remaining = deadline - time.monotonic()

            if remaining <= 0:
                raise TimeoutError(
                    f"No COMMAND_ACK received for MAV_CMD {command_id}."
                )

            try:
                ack = waiter.get(timeout=remaining)
            except queue.Empty:
                raise TimeoutError(
                    f"No COMMAND_ACK received for MAV_CMD {command_id}."
                )

            result = ack.result

            if result == mavutil.mavlink.MAV_RESULT_IN_PROGRESS:
                # Command is still in progress. Keep waiting for
                # the final ACK, if the autopilot provides one.
                continue

            if result == mavutil.mavlink.MAV_RESULT_ACCEPTED:
                return ack

            raise RuntimeError(
                f"Autopilot rejected MAV_CMD {command_id}; "
                f"MAV_RESULT={result}"
            )

    finally:
        with ack_lock:
            if ack_waiters.get(command_id) is waiter:
                del ack_waiters[command_id]


# ============================================================
# FLIGHT MODE
# ============================================================

def set_copter_mode(mode_name, timeout=10):
    """
    Request an ArduCopter mode and verify it using HEARTBEAT.

    SET_MODE does not use COMMAND_ACK in the same way as COMMAND_LONG.
    Therefore, the resulting heartbeat mode is checked instead.
    """
    ensure_fresh_heartbeat()

    mode_mapping = mav.mode_mapping()

    if not mode_mapping or mode_name not in mode_mapping:
        raise ValueError(
            f"Flight mode {mode_name!r} is not available. "
            f"Available modes: {list(mode_mapping or {})}"
        )

    mode_id = mode_mapping[mode_name]

    print(f"[MAVLink] Requesting flight mode {mode_name}")

    with mav_lock:
        mav.mav.set_mode_send(
            mav.target_system,
            mavutil.mavlink.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED,
            mode_id,
        )

    wait_for_state(
        lambda state: state["custom_mode"] == mode_id,
        timeout,
    )

    print(f"[MAVLink] Flight mode confirmed: {mode_name}")


# ============================================================
# ARM / DISARM
# ============================================================

def arm_or_disarm(arm):
    """
    Request normal arming/disarming and verify HEARTBEAT state.

    This deliberately does not use ArduPilot's force-disarm mechanism.
    """
    ensure_fresh_heartbeat()

    desired_state = bool(arm)

    if vehicle_state.get("armed") is desired_state:
        print(
            "[MAVLink] Vehicle already in desired armed state:",
            desired_state,
        )
        return

    command_id = (
        mavutil.mavlink.MAV_CMD_COMPONENT_ARM_DISARM
    )

    send_command_long(
        command_id=command_id,
        param1=1 if arm else 0,
    )

    wait_for_state(
        lambda state: state["armed"] is desired_state,
        STATE_CONFIRM_TIMEOUT,
    )

    print(
        "[MAVLink] Armed state confirmed:",
        desired_state,
    )


# ============================================================
# TAKEOFF
# ============================================================

def takeoff(altitude):
    """
    Request a guided takeoff.

    A command ACK is not proof of reaching the target altitude.
    """
    if isinstance(altitude, str):
        altitude = (
            altitude.strip()
            .lower()
            .removesuffix("metres")
            .removesuffix("meters")
            .removesuffix("m")
            .strip()
        )
    try:
        altitude = float(altitude)
    except (TypeError, ValueError):
        raise ValueError("Takeoff altitude must be a number.")

    if not MIN_TAKEOFF_ALTITUDE <= altitude <= MAX_TAKEOFF_ALTITUDE:
        raise ValueError(
            f"Takeoff altitude must be between "
            f"{MIN_TAKEOFF_ALTITUDE} and {MAX_TAKEOFF_ALTITUDE} metres."
        )

    ensure_fresh_heartbeat()

    set_copter_mode("GUIDED")

    if not is_armed():
        arm_or_disarm(True)

    print(f"[MAVLink] Requesting takeoff to {altitude} metres")

    command_id = mavutil.mavlink.MAV_CMD_NAV_TAKEOFF

    send_command_long(
        command_id=command_id,
        param1=0,
        param2=0,
        param3=0,
        param4=0,
        param5=0,
        param6=0,
        param7=altitude,
    )

    print(
        "[MAVLink] Takeoff command acknowledged. "
        "Continue monitoring altitude to verify completion."
    )

def handle_set_mode(command_id, drone_id, parameters):
    mode = str(parameters.get("mode", "")).upper().strip()

    if not mode:
        raise ValueError("Missing mode parameter.")

    # Normalize common frontend naming differences.
    mode_aliases = {
        "POS_HOLD": "POSHOLD",
    }
    mode = mode_aliases.get(mode, mode)

    # This requests the mode and waits for HEARTBEAT confirmation.
    # It uses the existing global `mav` connection.
    set_copter_mode(mode)

    send_command_status(
        command_id,
        drone_id,
        "MODE_CONFIRMED",
        f"Flight mode {mode} confirmed by MAVLink heartbeat.",
    )

# ============================================================
# LAND
# ============================================================

def land():
    """
    Request LAND mode and confirm the mode change.

    LAND mode confirmation does not mean touchdown has occurred.
    """
    ensure_fresh_heartbeat()

    set_copter_mode("LAND")

    print(
        "[MAVLink] LAND mode confirmed. "
        "Continue monitoring telemetry until landing is verified."
    )


# ============================================================
# COMMAND VALIDATION
# ============================================================

ALLOWED_COMMANDS = {
    "ARM",
    "DISARM",
    "TAKEOFF",
    "LAND",
    "SET_MODE",
    "FORCE_DISARM",
}

# ============================================================
# START MISSION
# ============================================================

def handle_start_mission(command_id, drone_id):
    """
    Start an already-uploaded ArduCopter mission.

    Safety requirements:
    - A fresh vehicle heartbeat must be available.
    - The vehicle must already be armed.
    - A mission must have been uploaded to the autopilot.
    - AUTO mode must be confirmed before reporting success.
    """
    ensure_fresh_heartbeat()

    if not is_armed():
        raise RuntimeError(
            "Cannot start mission: vehicle is not armed. "
            "Arm the vehicle after completing pre-flight checks."
        )

    print("[MISSION] Requesting AUTO mode.")

    # On ArduCopter, AUTO mode runs the uploaded autonomous mission.
    set_copter_mode("AUTO")

    send_command_status(
        command_id,
        drone_id,
        "MODE_CONFIRMED",
        "AUTO mode confirmed. The autopilot should execute its "
        "uploaded mission. Monitor mission progress and telemetry.",
    )

    print("[MISSION] AUTO mode confirmed.")

def remember_command_id(command_id):
    """
    Reject command IDs already in the recent history.
    """
    with command_ids_lock:
        if command_id in recent_command_ids_set:
            return False

        if len(recent_command_ids) == recent_command_ids.maxlen:
            oldest = recent_command_ids.popleft()
            recent_command_ids_set.discard(oldest)

        recent_command_ids.append(command_id)
        recent_command_ids_set.add(command_id)

        return True


def validate_command(message):
    command_id = message.get("commandId")
    drone_id = message.get("droneId")
    command_type = str(
        message.get("commandType", "")
    ).upper()

    parameters = message.get("parameters") or {}

    if not isinstance(parameters, dict):
        raise ValueError("parameters must be a JSON object.")

    if not command_id:
        raise ValueError("commandId is required.")

    if not drone_id:
        raise ValueError("droneId is required.")

    if drone_id != DRONE_ID:
        raise ValueError(
            f"This bridge controls {DRONE_ID}, not {drone_id}."
        )

    if command_type not in ALLOWED_COMMANDS:
        raise ValueError(
            f"Unsupported command: {command_type}"
        )

    if not remember_command_id(str(command_id)):
        raise ValueError(
            f"Duplicate commandId: {command_id}"
        )

    return str(command_id), drone_id, command_type, parameters


# ============================================================
# COMMAND EXECUTION
# ============================================================

def execute_command(message):
    """
    Execute a command and report its status to Spring Boot.
    """
    command_id = message.get("commandId")
    drone_id = message.get("droneId")

    try:
        command_id, drone_id, command_type, parameters = (
            validate_command(message)
        )

        print()
        print("=" * 55)
        print(f"COMMAND: {command_type}")
        print(f"Drone:   {drone_id}")
        print(f"ID:      {command_id}")
        print("=" * 55)

        send_command_status(
            command_id,
            drone_id,
            "RECEIVED",
        )

        # All flight commands execute sequentially.
        if command_type == "ARM":
            arm_or_disarm(True)

            send_command_status(
                command_id,
                drone_id,
                "STATE_CONFIRMED",
                "Armed state confirmed by MAVLink heartbeat.",
            )

        elif command_type == "DISARM":
            arm_or_disarm(False)

            send_command_status(
                command_id,
                drone_id,
                "STATE_CONFIRMED",
                "Disarmed state confirmed by MAVLink heartbeat.",
            )

        elif command_type == "SET_MODE":
            handle_set_mode(command_id, drone_id, parameters)

        elif command_type == "FORCE_DISARM":
            handle_start_mission(command_id, drone_id)

        elif command_type == "TAKEOFF":
            altitude = parameters.get(
                "altitude",
                parameters.get("targetAltitude", 5),
            )

            takeoff(altitude)

            send_command_status(
                command_id,
                drone_id,
                "ACCEPTED",
                "Autopilot acknowledged takeoff request. "
                "Target altitude has not yet been verified.",
            )

        elif command_type == "LAND":
            land()

            send_command_status(
                command_id,
                drone_id,
                "MODE_CONFIRMED",
                "LAND mode confirmed. Touchdown has not yet "
                "been verified.",
            )

    except Exception as exc:
        print(
            f"[COMMAND] Command {command_id} failed:",
            exc,
        )

        # Report failure if the command ID and drone ID are present.
        if command_id and drone_id:
            send_command_status(
                command_id,
                drone_id,
                "FAILED",
                str(exc),
            )


def command_worker():
    """
    One worker serializes flight commands to avoid conflicting actions.
    """
    print("[COMMAND] Worker started.")

    while True:
        message = command_queue.get()

        try:
            execute_command(message)
        finally:
            command_queue.task_done()


# ============================================================
# WEBSOCKET CALLBACKS
# ============================================================

def on_open(ws):
    print()
    print("=" * 55)
    print("WebSocket connected:", WS_URL)
    print("=" * 55)

    # AUTH must be the first application message.
    ws.send(json.dumps({
        "type": "AUTH",
        "token": JWT_TOKEN,
    }))

    print("[WebSocket] AUTH message sent.")


def on_message(ws, message):
    try:
        data = json.loads(message)
    except json.JSONDecodeError:
        print("[WebSocket] Invalid JSON received.")
        return

    message_type = data.get("type")

    # --------------------------------------------------------
    # Authentication
    # --------------------------------------------------------

    if message_type == "AUTH_SUCCESS":
        authenticated.set()

        print("[WebSocket] Authentication successful.")

        # Register this bridge with Spring Boot.
        registered = send_ws_message({
            "type": "REGISTER_DRONE",
            "droneId": DRONE_ID,
        })

        if registered:
            print(f"[WebSocket] Registration requested for {DRONE_ID}")
        else:
            print("[WebSocket] Failed to send drone registration.")

    # --------------------------------------------------------
    # Commands sent from Spring Boot
    # --------------------------------------------------------
    elif message_type == "DRONE_REGISTERED":
        registered_drone_id = message.get("droneId")

        if registered_drone_id == DRONE_ID:
            print(
                f"[WebSocket] Command bridge registered successfully "
                f"for {registered_drone_id}."
            )
        else:
            print(
                f"[WebSocket] WARNING: Registered drone ID "
                f"does not match configured ID: {registered_drone_id}"
            )
    elif message_type == "COMMAND":
        if not authenticated.is_set():
            print(
                "[COMMAND] Rejected: WebSocket is not authenticated."
            )
            return

        command_id = data.get("commandId")
        drone_id = data.get("droneId")

        print(
            f"[COMMAND] Received command={data.get('commandType')} "
            f"drone={drone_id} id={command_id}"
        )

        command_queue.put(data)

    # --------------------------------------------------------
    # Telemetry published by Spring Boot
    # --------------------------------------------------------

    elif message_type == "TELEMETRY":
        print(
            "[WebSocket] Telemetry received from server:",
            data.get("droneId"),
        )

    # --------------------------------------------------------
    # Other protocol messages
    # --------------------------------------------------------

    elif message_type == "PONG":
        pass

    elif message_type == "ERROR":
        print(
            "[WebSocket] Server error:",
            data.get("message"),
        )

    else:
        print(
            "[WebSocket] Unhandled message type:",
            message_type,
        )

def on_error(ws, error):
    print("[WebSocket] Error:", error)


def on_close(ws, close_status_code, close_msg):
    authenticated.clear()

    print()
    print("=" * 55)
    print("WebSocket connection closed")
    print("Status:", close_status_code)
    print("Message:", close_msg)
    print("=" * 55)


# ============================================================
# WEBSOCKET KEEPALIVE
# ============================================================

def keepalive_worker():
    """
    Send application-level PING messages every 30 seconds.
    """
    while True:
        time.sleep(30)

        if authenticated.is_set():
            send_ws_message({
                "type": "PING",
            })


# ============================================================
# MAIN
# ============================================================

def main():
    global JWT_TOKEN, ws_app

    # 1. Authenticate with Spring Boot.
    JWT_TOKEN = login()

    # 2. Connect to ArduPilot SITL.
    connect_to_sitl()

    # 3. Start the serialized command worker.
    threading.Thread(
        target=command_worker,
        daemon=True,
        name="command-worker",
    ).start()

    # 4. Start WebSocket keepalive.
    threading.Thread(
        target=keepalive_worker,
        daemon=True,
        name="websocket-keepalive",
    ).start()

    # 5. Connect the WebSocket client.
    ws_app = websocket.WebSocketApp(
        WS_URL,
        on_open=on_open,
        on_message=on_message,
        on_error=on_error,
        on_close=on_close,
    )

    print()
    print("DroneIQ Python bridge starting")
    print("Drone ID:", DRONE_ID)
    print("WebSocket:", WS_URL)
    print("MAVLink:", MAVLINK_CONNECTION)
    print()

    # Automatic reconnection can be added after ensuring that
    # stale commands are not replayed and the session is reauthorized.
    ws_app.run_forever()


if __name__ == "__main__":
    try:
        main()

    except KeyboardInterrupt:
        print("\nBridge stopped by user.")

    except Exception as exc:
        print("\nBridge startup failed:", exc)