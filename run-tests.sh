#!/bin/bash

# Set server and port
SERVER="localhost"
PORT="5128"

# Test --create
echo "Testing --create"
CREATE_OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --create AlexL Alex --password password123)
echo "CREATE_OUTPUT"
echo "Extracted UUID: $CREATE_OUTPUT"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --create with no real name specified
echo "Test --create with no real name specified"
CREATE_OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --create kai  --password pass)
echo "$CREATE_OUTPUT"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --create with no password specified
echo "Test --create with no password specified"
CREATE_OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --create Amit "Amit Jain")
echo "$CREATE_OUTPUT"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --create with name that is already used
echo "Test --create with name that is already used"
CREATE_OUTPUT=$(java -cp .:lib/* IdClient --server $SERVER --numport $PORT --create AlexL Alex)
echo "$CREATE_OUTPUT"
echo "------------------------------------------"
echo
echo
echo

sleep 2


# Test --lookup
echo "Testing --lookup"
java -cp .:lib/* IdClient --server $SERVER --numport $PORT --lookup AlexL
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --get users
echo "Testing --get users"
java -cp .:lib/* IdClient --server $SERVER --numport $PORT --get users
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --get uuids
echo "Testing --get uuids"
java -cp .:lib/* IdClient --server $SERVER --numport $PORT --get uuids
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --get all
echo "Testing --get all"
java -cp .:lib/* IdClient --server $SERVER --numport $PORT --get all
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --reverse-lookup with the extracted UUID
# echo "Testing --reverse-lookup with extracted UUID"
# java -cp .:lib/* IdClient --server $SERVER --numport $PORT --reverse-lookup $UUID
# echo "------------------------------------------"

# sleep 2

# Test --modify with password
echo "Testing --modify with password"
java -cp .:lib/* IdClient --server $SERVER --numport $PORT --modify AlexL ALEX --password "password123"
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --modify with no password
echo "Testing --modify with no password"
java -cp .:lib/* IdClient --server $SERVER --numport $PORT --modify Amit amit
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --modify with name that already exists
echo "Testing --modify with name that already exists"
java -cp .:lib/* IdClient --server $SERVER --numport $PORT --modify amit kai
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --delete no pass
echo "Testing --delete no password"
java -cp .:lib/* IdClient --server $SERVER --numport $PORT --delete amit
echo "------------------------------------------"
echo
echo
echo

sleep 2

# Test --delete with password
echo "Testing --delete with password"
java -cp .:lib/* IdClient --server $SERVER --numport $PORT --delete kai -password pass
echo "------------------------------------------"
echo
echo
echo

sleep 2
echo
echo
echo
echo "Testing Completed"
