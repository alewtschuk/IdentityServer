FROM eclipse-temurin:11-jdk-jammy

# Install make, redis-tools, and redis-server
RUN apt-get update && apt-get install -y make redis-tools redis-server

# Distrust the compromised eMudhra intermediates (CVE-2026-91403, CVE-2026-114820) until
# Temurin 11 ships the fix. https://openjdk.org/groups/vulnerability/advisories/2026-10-21
# SHA-256 fingerprints from https://emudhra.we-have-a-problem.com/impacted-intermediates.json
RUN for fp in \
    0164D7CEAE5BB909C272AF151FF44B1177AE9B47D9424B94F1D9558C30934D19 \
    42C4ACCD12C4F59597925E1A08A7A90102608543809308D50EC427E118BA1493 \
    432EC25F0D28DE9006D97BF752F17BBB2CD41F669933FCB072D9A1148D0261F3 \
    45A883F349AEDFD5F3A649B5498FFB121712BA8B448F3F2A91DBC5A766FCDDBE \
    4B2460A466F95FD40113BCDCCA2CEB29B3B093921DE45474DA7DF48874C630B1 \
    579480AEA166290BD3C643589E795FC0D0E54E86505D180F1DFF99EFD3199512 \
    5F1DED4DD590AF74631290CF073B122A2AAC6A1FA479778A0BD43579BD832699 \
    62B7F9868263F72F6911FC78CEAF72C1979158B7368BF648683BB2AA39B02E72 \
    7485B51C0ED192878FA1D2F948FB529AE1842CC5086C4B67F7BE5B2039DE9489 \
    84112972B8E59D14560AE7E7119654474AAD119990CF3722BFC1E3B91615E1FD \
    98B896625C0C230ADEEC3EB2CF6AAD5001ECF9156A68644E82374CE5833BC25B \
    9E39DE2881273ACC225A149C72BA3E0F51424963669AB633EE1DC7DF5DB067B4 \
    A496E23AC0CAB8155FC9B117B97EAE368EFCBB9041AEC8804D5A308D2D01C2D8 \
    CB6E15165AC802E9B3F993F16C25D41F4E81C21EBDE71AE1B012AC00BF9C7C3F \
    E7568FE6E12331F4FD7DE61DA4C7559D0F8EB192A218D4741A3BA75184333838 \
    FD9D5C2DBBD4995AB69B8D50C439D9527B00529199C2FD01B2174EB58F07021F; \
    do grep -qx "$fp" "$JAVA_HOME/lib/security/blocked.certs" || echo "$fp" >> "$JAVA_HOME/lib/security/blocked.certs"; done

# Pre-create the Java user preferences folder for the client
RUN mkdir -p /root/.java/.userPrefs

# OpenJDK's interim guidance (https://openjdk.org/emudhra): remove the roots that chain to
# the compromised intermediates, which also covers re-encoded copies blocked.certs misses.
# OpenJDK lists emSign Root CA - G1/G2, emSign ECC Root CA - G3 and Comodo AAA; Temurin 11
# only ships G1 and ECC G3, under subject-based aliases. Each root is looked up by name and
# skipped if a newer base image no longer has it, so the build never fails on this step.
RUN for root in emsign_root_ca_-_g1 emsign_ecc_root_ca_-_g3; do \
      alias=$(keytool -list -cacerts -storepass changeit 2>/dev/null | sed -n "s/^\(cn=$root,[^ ]*\), .*/\1/p"); \
      if [ -z "$alias" ]; then echo "Root $root not in cacerts, nothing to remove"; \
      else keytool -delete -cacerts -storepass changeit -alias "$alias" && echo "Removed root $alias" \
        || echo "Could not remove root $alias"; fi; \
    done

WORKDIR /app

COPY . .

# TLS keystores for RMI over SSL, generated per image so no private key is committed
RUN ./gen-certs.sh

RUN make

# No CMD here
