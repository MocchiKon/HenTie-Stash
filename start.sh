#!/bin/sh
# Runs from its own folder, so db/, data/ and bin/ are found beside the jar wherever it is started from.
cd "$(dirname "$0")" || exit 1

echo "============================================"
echo "  HenTie - starting up..."
echo "============================================"
echo
echo "The app will open in your browser automatically."
echo "If it does not, open:  http://localhost:8080"
echo
echo "To use it from your phone or another device on the"
echo "same network, open:    http://THIS-PC-IP:8080"
echo "(run 'ip addr' to find THIS-PC-IP)"
echo
echo "To stop it, press Ctrl+C, or use Shut down on the Settings page."
echo "============================================"
echo

if ! command -v java >/dev/null 2>&1; then
    echo "Java was not found. Please install Java 21 or newer from:"
    echo "  https://adoptium.net/"
    exit 1
fi

java -jar HenTie.jar
echo
echo "HenTie has stopped."
