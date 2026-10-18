(ns hive-artcraft.port
  "Boundary port for a live app control channel. Socket, control-file or native adapters implement it.")

(defprotocol ControlTransport
  (send-request [transport request] "Send one validated control request and return the decoded reply value."))
