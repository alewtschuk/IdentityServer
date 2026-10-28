#!/bin/bash

# Runs inside the client container:
#   make test   (or: docker compose exec client bash run-tests.sh)
SERVER="docker.server"
PORT="5128"

pass=0
fail=0
# check <test name> <output> <expected text>: records PASS if the output contains the expected text
check() { if echo "$2" | grep -q "$3"; then echo "PASS: $1"; pass=$((pass+1)); else echo "FAIL: $1"; fail=$((fail+1)); fi; }
# check_not <test name> <output> <unexpected text>: records PASS if the output does not contain the text
check_not() { if echo "$2" | grep -q "$3"; then echo "FAIL: $1"; fail=$((fail+1)); else echo "PASS: $1"; pass=$((pass+1)); fi; }

# Test no command
echo "Testing no command"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT 2>&1)
echo "$OUTPUT"
check "no command prints usage" "$OUTPUT" "USAGE:"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --create
echo "Testing --create"
CREATE_OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --create alex Alex --password password123)
echo "$CREATE_OUTPUT"
check "--create" "$CREATE_OUTPUT" "created on server"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --create with no real name specified
echo "Test --create with no real name specified"
CREATE_OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --create kai  --password pass)
echo "$CREATE_OUTPUT"
check "--create with no real name" "$CREATE_OUTPUT" "created on server"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --create with no password specified
echo "Test --create with no password specified"
CREATE_OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --create Amit "Amit Jain")
echo "$CREATE_OUTPUT"
check "--create with no password" "$CREATE_OUTPUT" "created on server"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --create with name that is already used
echo "Test --create with name that is already used"
CREATE_OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --create alex Alex)
echo "$CREATE_OUTPUT"
check "--create with used name" "$CREATE_OUTPUT" "already exists"
echo "------------------------------------------"
echo
echo
echo

sleep 2


# Test --lookup
echo "Testing --lookup"
LOOKUP_OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --lookup alex)
echo "$LOOKUP_OUTPUT"
UUID=$(echo "$LOOKUP_OUTPUT" | grep -o '[0-9a-f]\{8\}-[0-9a-f]\{4\}-[0-9a-f]\{4\}-[0-9a-f]\{4\}-[0-9a-f]\{12\}' | head -1)
echo "Extracted UUID: $UUID"
check "--lookup" "$LOOKUP_OUTPUT" "FOR USER: alex"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --get users
echo "Testing --get users"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --get users)
echo "$OUTPUT"
check "--get users" "$OUTPUT" "Registered USERS"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --get uuids
echo "Testing --get uuids"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --get uuids)
echo "$OUTPUT"
check "--get uuids" "$OUTPUT" "Registered UUIDS"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --get all
echo "Testing --get all"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --get all)
echo "$OUTPUT"
check "--get all" "$OUTPUT" "Registered Accounts"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --get with an invalid value
echo "Testing --get with an invalid value"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --get bogus)
echo "$OUTPUT"
check "--get bogus" "$OUTPUT" "INVALID ARG WITH -get: bogus"
check_not "--get bogus has no null prefix" "$OUTPUT" "null"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --reverse-lookup with the extracted UUID
echo "Testing --reverse-lookup with extracted UUID"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --reverse-lookup $UUID)
echo "$OUTPUT"
check "--reverse-lookup" "$OUTPUT" "FOR USER: alex"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --reverse-lookup with a malformed UUID
echo "Testing --reverse-lookup with a malformed UUID"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --reverse-lookup abc)
echo "$OUTPUT"
check "--reverse-lookup malformed UUID" "$OUTPUT" "invalid UUID: abc"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --reverse-lookup with an unknown UUID
echo "Testing --reverse-lookup with an unknown UUID"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --reverse-lookup 00000000-0000-0000-0000-000000000000)
echo "$OUTPUT"
check "--reverse-lookup unknown UUID" "$OUTPUT" "not found"
check_not "--reverse-lookup unknown UUID is not labeled as found" "$OUTPUT" "Account info found"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --modify with wrong password (the account must not be rewritten)
echo "Testing --modify with wrong password"
BEFORE=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --lookup alex | grep "Recent Activity")
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --modify alex ALEX --password wrong)
echo "$OUTPUT"
check "--modify with wrong password" "$OUTPUT" "PASSWORD DENIED"
AFTER=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --lookup alex | grep "Recent Activity")
echo "Before: $BEFORE"
echo "After:  $AFTER"
check "--modify with wrong password leaves the account unchanged" "$BEFORE" "${AFTER:-missing}"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --modify with password
echo "Testing --modify with password"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --modify alex ALEX --password "password123")
echo "$OUTPUT"
check "--modify with password" "$OUTPUT" "login name updated to ALEX"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --modify with no password
echo "Testing --modify with no password"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --modify Amit amit)
echo "$OUTPUT"
check "--modify with no password" "$OUTPUT" "login name updated to amit"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --modify with name that already exists
echo "Testing --modify with name that already exists"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --modify amit kai)
echo "$OUTPUT"
check "--modify with used name" "$OUTPUT" "already exists"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --delete no pass
echo "Testing --delete no password"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --delete amit)
echo "$OUTPUT"
check "--delete no password" "$OUTPUT" "removed"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --delete with password
echo "Testing --delete with password"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --delete kai -password pass)
echo "$OUTPUT"
check "--delete with password" "$OUTPUT" "removed"
echo "------------------------------------------"
echo
echo
echo

sleep 2

echo "Testing --delete with wrong password"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --delete ALEX --password wrong)
echo "$OUTPUT"
check "--delete with wrong password" "$OUTPUT" "PASSWORD DENIED"
echo
echo
echo

sleep 2

echo "Testing --delete with correct password"
OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --delete ALEX --password password123)
echo "$OUTPUT"
check "--delete with correct password" "$OUTPUT" "removed"
echo
echo
echo

sleep 2
echo
echo
echo
echo "Testing Completed: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
