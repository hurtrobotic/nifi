NIFI_BASE_DIR="/opt/nifi"
NIFI_VERSION="1.6.0"
NIFI_HOME=${NIFI_BASE_DIR}/nifi-${NIFI_VERSION}  
if [ -d "${NIFI_HOME}/logs" ]; then rm ${NIFI_HOME}/logs/*.log; fi
