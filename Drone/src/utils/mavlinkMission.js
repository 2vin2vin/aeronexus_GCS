const MAVLINK_COMMAND_TYPES = {
  16: "WAYPOINT",
  20: "RTL",
  21: "LAND",
  22: "TAKEOFF",
}

export function mapMavlinkWaypointsToPlannerItems(droneId, waypoints) {
  if (!Array.isArray(waypoints)) return []

  return waypoints
    .filter((waypoint) => {
      const latitude = Number(waypoint.latitude)
      const longitude = Number(waypoint.longitude)
      return Number.isFinite(latitude) &&
        Number.isFinite(longitude) &&
        latitude >= -90 && latitude <= 90 &&
        longitude >= -180 && longitude <= 180 &&
        !(Math.abs(latitude) < 0.0001 && Math.abs(longitude) < 0.0001)
    })
    .map((waypoint, index) => {
      const sequence = Number.isInteger(Number(waypoint.sequence))
        ? Number(waypoint.sequence)
        : index
      const type = MAVLINK_COMMAND_TYPES[Number(waypoint.command)] || "WAYPOINT"
      const altitude = Number(waypoint.altitude)

      return {
        id: `${droneId}-mavlink-${sequence}`,
        seq: sequence + 1,
        type,
        lat: Number(Number(waypoint.latitude).toFixed(6)),
        lng: Number(Number(waypoint.longitude).toFixed(6)),
        alt: Number.isFinite(altitude) ? Math.max(0, altitude) : 0,
        speed: 0,
        holdTime: 0,
        heading: "Auto",
        command: Number(waypoint.command),
        frame: Number(waypoint.frame),
      }
    })
}