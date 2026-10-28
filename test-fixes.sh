#!/bin/bash
# Shows the client- and server-visible behavior fixes against the running cluster.
# Designed to run on the host because it reads server IPs and edits the client's
# saved coordinator address.
#
#   1. a client run with no command prints usage
#   2. bad -g values and bad or unknown UUIDs return clean errors
#   3. a wrong password on modify does not touch the account
#   4. -s with a single hostname or IP redirects to the coordinator
#   5. a stale saved coordinator address does not stop a single-host client
#   6. the compromised eMudhra roots are not trusted by the image's JDK
#
# Each step prints the client output, then PASS or FAIL.
#
# Usage: ./test-fixes.sh
COMPOSE=${COMPOSE:-docker compose}
TEST_USER="fixes-test-$$"
failures=0

pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1"; failures=$((failures + 1)); }

# runs IdClient with the given options (no -s added) and prints the command first
client_raw() {
    echo "  \$ IdClient $*" >&2
    $COMPOSE exec -T client java -cp '.:lib/*' IdClient "$@" 2>&1
}
client() { client_raw -s docker.server "$@"; }

# shows the output indented, then checks it for the expected text
check() {
    echo "$2" | grep -v '^$' | sed 's/^/    /'
    echo "$2" | grep -q -- "$3" && pass "$1" || fail "$1"
    echo
}

# prints "master" or "slave" for a server's redis
role() {
    $COMPOSE exec -T "$1" redis-cli info replication 2>/dev/null | tr -d '\r' | sed -n 's/^role://p'
}

prefs_file() {
    $COMPOSE exec -T client bash -c 'find /root/.java/.userPrefs -name prefs.xml 2>/dev/null | head -1'
}

SERVERS=$($COMPOSE ps --services --status running | grep '^id-server-' | sort)
[ $(echo $SERVERS | wc -w) -ge 2 ] || { echo "Start the cluster first: make up"; exit 1; }
replica=""
for s in $SERVERS; do
    [ "$(role "$s")" = "slave" ] && replica=$s && break
done
[ -n "$replica" ] || { echo "No replica found; wait for the election to finish and retry"; exit 1; }
replica_ip=$($COMPOSE exec -T "$replica" hostname -i | tr -d '\r' | awk '{print $1}')
echo "Servers: $(echo $SERVERS) (using replica $replica at $replica_ip)"
echo

echo "1. No command prints usage"
check "usage printed instead of a crash" "$(client)" "USAGE:"

echo "2. Bad input returns clean errors"
client -c "$TEST_USER" -p pw > /dev/null
check "-g bogus has no null prefix" "$(client -g bogus)" "\[FROM SERVER\]: \[.*ERROR.*\].*INVALID ARG"
check "-r abc is rejected as an invalid UUID" "$(client -r abc)" "invalid UUID: abc"
out=$(client -r 00000000-0000-0000-0000-000000000000)
echo "$out" | grep -q "Account info found" && fail "unknown UUID still labeled 'Account info found'" \
    || check "unknown UUID shows only the error" "$out" "not found"
uuid=$(client -l "$TEST_USER" 2>/dev/null | grep -o '[0-9a-f]\{8\}-[0-9a-f-]\{27\}' | head -1)
check "real UUID is labeled 'Account info found'" "$(client -r "$uuid")" "Account info found FOR USER: $TEST_USER"

echo "3. Wrong password on modify leaves the account untouched"
before=$(client -l "$TEST_USER" 2>/dev/null | grep 'Recent Activity')
check "modify with wrong password is denied" "$(client -m "$TEST_USER" "$TEST_USER-renamed" -p wrong)" "PASSWORD DENIED"
after=$(client -l "$TEST_USER" 2>/dev/null | grep 'Recent Activity')
echo "    before: $before"
echo "    after:  $after"
[ -n "$before" ] && [ "$before" = "$after" ] && pass "account was not rewritten" || fail "account was rewritten"
echo

echo "4. A single host redirects to the coordinator"
$COMPOSE exec -T client bash -c 'rm -rf /root/.java/.userPrefs/*'
check "-s $replica (hostname) is redirected" "$(client_raw -s "$replica" -l "$TEST_USER")" "Found Coordinator Address"
$COMPOSE exec -T client bash -c 'rm -rf /root/.java/.userPrefs/*'
check "-s $replica_ip (IP address) is redirected" "$(client_raw -s "$replica_ip" -l "$TEST_USER")" "Found Coordinator Address"

echo "5. A stale saved coordinator does not block a single host"
stale="${replica_ip%.*}.250" # unused address on the cluster network
pf=$(prefs_file | tr -d '\r')
$COMPOSE exec -T client sed -i "s/key=\"coordinatorAddress\" value=\"[^\"]*\"/key=\"coordinatorAddress\" value=\"$stale\"/" "$pf"
echo "    saved coordinator set to $stale"
out=$(client_raw -s "$replica" -l "$TEST_USER" -dx)
echo "$out" | grep -E 'trying this host|reset due to' | sed 's/^/    /'
echo "$out" | grep -q "FOR USER: $TEST_USER" && pass "stale address skipped, $replica tried and redirected" \
    || fail "client gave up before trying $replica"
echo

echo "6. Compromised eMudhra roots are not trusted"
roots=$($COMPOSE exec -T client keytool -list -cacerts -storepass changeit 2>/dev/null \
    | grep -ciE '^cn=emsign_(root_ca_-_g1|ecc_root_ca_-_g3),')
echo "    emSign G1 / ECC G3 roots in cacerts: $roots"
[ "$roots" = "0" ] && pass "roots removed from the JDK trust store" || fail "roots still trusted"
echo

client -d "$TEST_USER" -p pw > /dev/null 2>&1 # clean up

[ $failures -eq 0 ] && echo "All fix checks passed" || echo "$failures check(s) failed"
exit $failures
