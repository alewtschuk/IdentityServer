#!/bin/bash
# Tests the bully election and failover against the running cluster.
# Desigend to run on the host because it stops and starts server containers.
#
#   1. one coordinator is elected and every other server follows it
#   2. an account is created through the coordinator
#   3. the coordinator is stopped and the remaining servers elect a new one
#   4. the account is still there (no state lost in the transition)
#   5. the old coordinator is restarted and rejoins as a replica
#
# A server's role is read from its redis: the coordinator's redis is the
# replication master, every replica's redis is a slave of it.
#
# Usage: ./test-failover.sh   (or: make failover)
COMPOSE=${COMPOSE:-docker-compose}
SERVERS=$($COMPOSE ps --services --status running | grep '^id-server-' | sort)
TEST_USER="failover-test-$$"
failures=0

pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1"; failures=$((failures + 1)); }

# prints "master" or "slave" for a server's redis
role() {
    $COMPOSE exec -T "$1" redis-cli info replication 2>/dev/null | tr -d '\r' | sed -n 's/^role://p'
}

# prints the single coordinator once exactly one master is followed by every other server
coordinator() {
    local masters="" others=0 linked=0
    for s in $SERVERS; do
        case $(role "$s") in
            master) masters="$masters $s" ;;
            slave)
                others=$((others + 1))
                $COMPOSE exec -T "$s" redis-cli info replication | grep -q 'master_link_status:up' && linked=$((linked + 1))
                ;;
        esac
    done
    [ $(echo $masters | wc -w) -eq 1 ] && [ $linked -eq $others ] && echo $masters
}

# waits up to 30 seconds for an agreed coordinator
wait_for_coordinator() {
    for i in $(seq 30); do
        c=$(coordinator)
        [ -n "$c" ] && echo "$c" && return 0
        sleep 1
    done
    return 1
}

client() {
    $COMPOSE exec -T client java -cp '.:lib/*' IdClient -s docker.server "$@" 2>&1
}

echo "Servers: $(echo $SERVERS)"
[ $(echo $SERVERS | wc -w) -ge 2 ] || { echo "Start the cluster first: make up"; exit 1; }

echo "1. Initial election"
first=$(wait_for_coordinator) && pass "$first is coordinator, all others are replicas" || fail "no single coordinator after 30s"

echo "2. Create an account"
client -c "$TEST_USER" -p pw "Failover Test" | grep -q "created on server" && pass "created $TEST_USER" || fail "could not create $TEST_USER"

echo "3. Stop coordinator $first"
$COMPOSE stop "$first" > /dev/null 2>&1
SERVERS=$(echo "$SERVERS" | grep -vx "$first")
second=$(wait_for_coordinator) && pass "$second elected as new coordinator" || fail "no new coordinator after 30s"

echo "4. Account survived the failover"
client -l "$TEST_USER" | grep -q "FOR USER: $TEST_USER" && pass "$TEST_USER found on $second" || fail "$TEST_USER lost"

echo "5. Restart $first"
$COMPOSE start "$first" > /dev/null 2>&1
SERVERS=$($COMPOSE ps --services --status running | grep '^id-server-' | sort)
sleep 5 # let it start and hear from the coordinator
third=$(wait_for_coordinator)
[ "$third" = "$second" ] && [ "$(role "$first")" = "slave" ] \
    && pass "$first rejoined as a replica of $second" || fail "$first did not rejoin as a replica (coordinator: ${third:-none})"

client -d "$TEST_USER" -p pw > /dev/null # clean up

echo
[ $failures -eq 0 ] && echo "All failover checks passed" || echo "$failures check(s) failed"
exit $failures
