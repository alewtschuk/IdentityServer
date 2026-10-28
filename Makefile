# IdentityServer demo
#   make              compiles Java source
#   make up           builds the image and starts the server cluster in the background
#   make logs         follows logs from every server
#   make monitor      runs make up and make logs to bring up the containers and drop into monitoring immediately after container start
#   make test         run the client test script against the cluster
#   make failover     stop the coordinator and check a new one is elected (test-failover.sh)
#   make down         stop the cluster and remove its containers (and their data)
#   make restart      stops any cluster that is running, removes its containers, rebuilds the image, containers, and brings them back up
#   make m-restart    performs make restart and drops into monitoring immediately after container start
#   make refresh      performs the same functionality as m-restart but, refreshes the environment by cleaning everything before restarting and monitoring
#   make clean        remove compiled classes and stops and removes cluster images

COMPOSE ?= docker compose

build:
	@javac -cp ".:lib/*" *.java

up:
	@$(COMPOSE) up --build -d

logs:
	@$(COMPOSE) logs -f

monitor:
	@$(MAKE) up && $(MAKE) logs

test:
	@$(COMPOSE) exec client bash run-tests.sh

failover:
	@./test-failover.sh

down:
	@$(COMPOSE) down

restart:
	@$(MAKE) down && $(MAKE) up

m-restart:
	@$(MAKE) down && $(MAKE) monitor

refresh:
	@$(MAKE) clean && $(MAKE) monitor

clean:
	@rm -vf *.class 2> /dev/null && $(COMPOSE) down --rmi all --remove-orphans

.PHONY: build up logs monitor test failover down restart m-restart refresh clean
