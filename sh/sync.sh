NIFI_BASE_DIR="/opt/nifi"
NIFI_VERSION="1.6.0"
NIFI_HOME=${NIFI_BASE_DIR}/nifi-${NIFI_VERSION} \
scripts_dir="${NIFI_BASE_DIR}/scripts"
if [ -d "${NIFI_BASE_DIR}/modules" ]; then cp -r -v ${NIFI_BASE_DIR}/modules/* ${NIFI_HOME}/lib/ | cat -n; fi
