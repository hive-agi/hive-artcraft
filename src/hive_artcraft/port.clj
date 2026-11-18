(ns hive-artcraft.port
  "Boundary port for a live app control channel. Socket, control-file or native adapters implement it.")

(defprotocol ControlTransport
  (send-request [transport request] "Send one validated control request and return the decoded reply value."))

(defprotocol LineSession
  "One open JSON-lines connection to a running app."
  (send-line! [session line] "Write one framed line (ending in a newline) and flush it.")
  (recv-line! [session] "Read one reply line without its newline; nil when the app closed the connection.")
  (close-lines! [session] "Release the connection."))

(defprotocol LineConnector
  "Opens JSON-lines connections. TCP in production; tests pass a scripted stub adapter."
  (open-lines [connector endpoint] "Connect to {:host :port :connect-ms :timeout-ms}; return a LineSession or throw."))
