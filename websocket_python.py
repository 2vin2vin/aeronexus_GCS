import json
import requests
import websocket


# ============================================================
# CONFIGURATION
# ============================================================

SERVER_HOST = "localhost"
SERVER_PORT = 8081

HTTP_BASE_URL = f"http://{SERVER_HOST}:{SERVER_PORT}"
WS_URL = f"ws://{SERVER_HOST}:{SERVER_PORT}/ws/telemetry"

LOGIN_URL = f"{HTTP_BASE_URL}/api/auth/login"


# ============================================================
# USER LOGIN
# ============================================================

USERNAME = "superadmin"
PASSWORD = "admin123"


# Global JWT token
JWT_TOKEN = None


# ============================================================
# STEP 1: LOGIN AND GET JWT
# ============================================================

def login():

    print("Logging in...")

    login_payload = {
        "username": USERNAME,
        "password": PASSWORD
    }

    response = requests.post(
        LOGIN_URL,
        json=login_payload,
        timeout=10
    )

    print("HTTP status:", response.status_code)

    # If Spring Boot returned an error
    if not response.ok:
        print("Login failed:")
        print(response.text)
        response.raise_for_status()

    response_json = response.json()

    print("Login response:")
    print(json.dumps(response_json, indent=2))

    # ========================================================
    # Your AuthController returns:
    #
    # ApiResponse<LoginResponse>
    #
    # Therefore the response should approximately be:
    #
    # {
    #     "success": true,
    #     "message": "Authentication successful",
    #     "data": {
    #         "token": "...",
    #         "expiration": ...,
    #         "username": "...",
    #         "email": "...",
    #         "role": "..."
    #     }
    # }
    #
    # ========================================================

    data = response_json.get("data")

    if data is None:
        raise Exception(
            "Login succeeded but 'data' was not found in response"
        )

    token = data.get("token")

    if not token:
        raise Exception(
            "Login succeeded but JWT 'token' was not found"
        )

    print("\nJWT received successfully")
    print("Token:", token)

    return token


# ============================================================
# STEP 2: WEBSOCKET CONNECTED
# ============================================================

def on_open(ws):

    print("\n========================================")
    print("WebSocket connected")
    print("========================================")

    # ========================================================
    # IMPORTANT:
    #
    # Your Java WebSocket handler requires AUTH
    # to be the FIRST message.
    # ========================================================

    auth_message = {
        "type": "AUTH",
        "token": JWT_TOKEN
    }

    ws.send(json.dumps(auth_message))

    print("AUTH message sent")


# ============================================================
# STEP 3: RECEIVE DATA FROM SERVER
# ============================================================

def on_message(ws, message):
    try:
        data = json.loads(message)
        message_type = data.get("type")

        if message_type == "AUTH_SUCCESS":
            print("WebSocket authentication successful")

        elif message_type == "TELEMETRY":
            drone_id = data.get("droneId")
            telemetry = data.get("data")
            print(f"Telemetry from {drone_id}: {telemetry}")

        elif message_type == "COMMAND":
            handle_command(ws, data)

        elif message_type == "PONG":
            print("PONG received")

        elif message_type == "ERROR":
            print("Server error:", data.get("message"))

        else:
            print("Unhandled message type:", message_type)

    except json.JSONDecodeError:
        print("Server sent invalid JSON")


def handle_command(ws, message):
    command_id = message.get("commandId")
    drone_id = message.get("droneId")
    command_type = message.get("commandType")
    parameters = message.get("parameters") or {}

    allowed_commands = {
        "ARM",
        "DISARM",
        "TAKEOFF",
        "LAND",
    }

    if not command_id or not drone_id:
        print("Rejected command: missing commandId or droneId")
        return

    if command_type not in allowed_commands:
        print("Rejected unsupported command:", command_type)
        return

    print(
        f"Received {command_type} for {drone_id}; "
        f"commandId={command_id}"
    )

    # TODO:
    # 1. Verify that this bridge controls drone_id.
    # 2. Validate the parameters.
    # 3. Send the command through MAVSDK/MAVLink to the SITL autopilot.
    # 4. Report the autopilot acknowledgement to Spring Boot.
    # 5. Verify the requested state through telemetry.
# ============================================================
# WEBSOCKET ERROR
# ============================================================

def on_error(ws, error):

    print("\nWebSocket error:")
    print(error)


# ============================================================
# WEBSOCKET CLOSED
# ============================================================

def on_close(ws, close_status_code, close_msg):

    print("\n========================================")
    print("WebSocket connection closed")
    print("Status:", close_status_code)
    print("Message:", close_msg)
    print("========================================")


# ============================================================
# SEND COMMAND TO SERVER
# ============================================================

def subscribe_to_drone(ws, drone_id):

    message = {
        "type": "SUBSCRIBE",
        "droneId": drone_id
    }

    ws.send(json.dumps(message))

    print(
        f"Subscription request sent for {drone_id}"
    )


def unsubscribe_from_drone(ws, drone_id):

    message = {
        "type": "UNSUBSCRIBE",
        "droneId": drone_id
    }

    ws.send(json.dumps(message))

    print(
        f"Unsubscribe request sent for {drone_id}"
    )


def ping_server(ws):

    message = {
        "type": "PING"
    }

    ws.send(json.dumps(message))

    print("PING sent")


# ============================================================
# MAIN
# ============================================================

if __name__ == "__main__":

    try:

        # ----------------------------------------------------
        # 1. Login
        # ----------------------------------------------------

        JWT_TOKEN = login()

        # ----------------------------------------------------
        # 2. Connect to WebSocket
        # ----------------------------------------------------

        print("\nConnecting to:")
        print(WS_URL)

        ws = websocket.WebSocketApp(
            WS_URL,
            on_open=on_open,
            on_message=on_message,
            on_error=on_error,
            on_close=on_close
        )

        # ----------------------------------------------------
        # 3. Start WebSocket
        # ----------------------------------------------------

        ws.run_forever()

    except Exception as e:

        print("\nApplication error:")
        print(e)