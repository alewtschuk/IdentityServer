#!/bin/bash
# Regenerates the TLS material for RMI over SSL.
#   Server_Keystore   - the servers' private key and certificate
#   server.cer        - that certificate on its own
#   Client_Truststore - the certificate the client trusts
# The certificate names every server hostname in docker.server (subject
# alternative names), so the client's hostname check passes whichever server
# it connects to.
#
# Runs during the Docker image build (see Dockerfile). Every container uses the
# same image, so the servers and the client always share the same certificate.
set -e
cd "$(dirname "$0")"

PASS=123456 # must match IdServer.main and IdClient.setupSSL
SAN="dns:localhost"
for host in $(tr ',' ' ' < docker.server); do
    SAN="$SAN,dns:$host"
done

rm -f Server_Keystore Client_Truststore server.cer
keytool -genkeypair -alias idserver -keyalg RSA -keysize 3072 -validity 3650 \
    -dname "CN=IdServer, OU=IdentityServer, O=IdentityServer, C=US" -ext "SAN=$SAN" \
    -keystore Server_Keystore -storetype PKCS12 -storepass $PASS -keypass $PASS
keytool -exportcert -alias idserver -keystore Server_Keystore -storepass $PASS -file server.cer
keytool -importcert -noprompt -alias idserver -file server.cer \
    -keystore Client_Truststore -storetype PKCS12 -storepass $PASS
