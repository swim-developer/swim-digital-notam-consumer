#!/bin/bash
set -e

BROKER_DIR=/var/lib/artemis-instance

if [ ! -f "$BROKER_DIR/etc/broker.xml" ]; then
  /opt/activemq-artemis/bin/artemis create "$BROKER_DIR" \
    --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD" \
    --http-host 0.0.0.0 \
    --silent --require-login --no-autotune
fi

cp /opt/broker-override/broker.xml "$BROKER_DIR/etc/broker.xml"

exec "$BROKER_DIR/bin/artemis" run
